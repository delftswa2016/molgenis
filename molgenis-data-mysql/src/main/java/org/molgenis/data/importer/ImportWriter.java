package org.molgenis.data.importer;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.molgenis.data.AttributeMetaData;
import org.molgenis.data.DataService;
import org.molgenis.data.DatabaseAction;
import org.molgenis.data.Entity;
import org.molgenis.data.EntityMetaData;
import org.molgenis.data.IndexedRepository;
import org.molgenis.data.MolgenisDataException;
import org.molgenis.data.Package;
import org.molgenis.data.Query;
import org.molgenis.data.Repository;
import org.molgenis.data.RepositoryCollection;
import org.molgenis.data.meta.TagMetaData;
import org.molgenis.data.semantic.LabeledResource;
import org.molgenis.data.semantic.Tag;
import org.molgenis.data.semantic.UntypedTagService;
import org.molgenis.data.support.DefaultEntity;
import org.molgenis.data.support.QueryImpl;
import org.molgenis.fieldtypes.FieldType;
import org.molgenis.framework.db.EntityImportReport;
import org.molgenis.security.core.utils.SecurityUtils;
import org.molgenis.security.permission.PermissionSystemService;
import org.molgenis.util.DependencyResolver;
import org.molgenis.util.HugeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * Writes the imported metadata and data to target {@link RepositoryCollection}.
 */
public class ImportWriter
{
	private static final Logger LOG = LoggerFactory.getLogger(ImportWriter.class);

	private final DataService dataService;
	private final PermissionSystemService permissionSystemService;
	private final UntypedTagService tagService;

	/**
	 * Creates the ImportWriter
	 * 
	 * @param dataService
	 *            {@link DataService} to query existing repositories and transform entities
	 * @param permissionSystemService
	 *            {@link PermissionSystemService} to give permissions on uploaded entities
	 */
	public ImportWriter(DataService dataService, PermissionSystemService permissionSystemService,
			UntypedTagService tagService)
	{
		this.dataService = dataService;
		this.permissionSystemService = permissionSystemService;
		this.tagService = tagService;
	}

	@Transactional
	public EntityImportReport doImport(EmxImportJob job)
	{
		importTags(job.source);
		importPackages(job.parsedMetaData);
		addEntityMetaData(job.parsedMetaData, job.report, job.metaDataChanges);
		addEntityPermissions(job.metaDataChanges);
		importEntityAndAttributeTags(job.parsedMetaData);
		importData(job.report, job.parsedMetaData.getEntities(), job.source, job.dbAction);
		return job.report;
	}

	private void importEntityAndAttributeTags(ParsedMetaData parsedMetaData)
	{
		for (Tag<EntityMetaData, LabeledResource, LabeledResource> tag : parsedMetaData.getEntityTags())
		{
			tagService.addEntityTag(tag);
		}

		for (EntityMetaData emd : parsedMetaData.getAttributeTags().keySet())
		{
			for (Tag<AttributeMetaData, LabeledResource, LabeledResource> tag : parsedMetaData.getAttributeTags().get(
					emd))
			{
				tagService.addAttributeTag(emd, tag);
			}
		}
	}

	/**
	 * Imports entity data for all entities in {@link #resolved} from {@link #source}
	 */
	private void importData(EntityImportReport report, Iterable<EntityMetaData> resolved, RepositoryCollection source,
			DatabaseAction dbAction)
	{
		for (final EntityMetaData entityMetaData : resolved)
		{
			String name = entityMetaData.getName();
			Repository repository = dataService.getRepository(name);

			if (repository != null)
			{
				Repository fileEntityRepository = source.getRepository(entityMetaData.getSimpleName());

				if (fileEntityRepository == null)
				{
					// Try fully qualified name
					fileEntityRepository = source.getRepository(entityMetaData.getName());
				}

				// check to prevent nullpointer when importing metadata only
				if (fileEntityRepository != null)
				{

					// transforms entities so that they match the entity meta data of the output repository
					Iterable<Entity> entities = Iterables.transform(fileEntityRepository,
							new Function<Entity, Entity>()
							{
								@Override
								public Entity apply(Entity entity)
								{
									return new DefaultEntity(entityMetaData, dataService, entity);
								}
							});

					entities = DependencyResolver.resolveSelfReferences(entities, entityMetaData);

					int count = update(repository, entities, dbAction);
					report.addEntityCount(name, count);
				}
			}
		}
	}

	/**
	 * Gives the user permission to see and edit his imported entities, unless the user is admin since admins can do
	 * that anyways.
	 */
	private void addEntityPermissions(MetaDataChanges metaDataChanges)
	{
		if (!SecurityUtils.currentUserIsSu())
		{
			permissionSystemService.giveUserEntityAndMenuPermissions(SecurityContextHolder.getContext(),
					metaDataChanges.getAddedEntities());
		}
	}

	/**
	 * Adds the parsed {@link ParsedMetaData}, creating new repositories where necessary.
	 */
	private void addEntityMetaData(ParsedMetaData parsedMetaData, EntityImportReport report,
			MetaDataChanges metaDataChanges)
	{
		for (EntityMetaData entityMetaData : parsedMetaData.getEntities())
		{
			String name = entityMetaData.getName();
			if (!EmxMetaDataParser.ENTITIES.equals(name) && !EmxMetaDataParser.ATTRIBUTES.equals(name)
					&& !EmxMetaDataParser.PACKAGES.equals(name) && !EmxMetaDataParser.TAGS.equals(name))
			{
				if (dataService.getMeta().getEntityMetaData(entityMetaData.getName()) == null)
				{
					LOG.debug("trying to create: " + name);
					metaDataChanges.addEntity(name);
					Repository repo = dataService.getMeta().addEntityMeta(entityMetaData);
					if (repo != null)
					{
						report.addNewEntity(name);
					}
				}
				else if (!entityMetaData.isAbstract())
				{
					List<AttributeMetaData> addedAttributes = dataService.getMeta().updateEntityMeta(entityMetaData);
					metaDataChanges.addAttributes(name, addedAttributes);
				}
			}
		}
	}

	/**
	 * Adds the packages from the packages sheet to the {@link #metaDataService}.
	 */
	private void importPackages(ParsedMetaData parsedMetaData)
	{
		for (Package p : parsedMetaData.getPackages().values())
		{
			if (p != null)
			{
				dataService.getMeta().addPackage(p);
			}
		}
	}

	/**
	 * Imports the tags from the tag sheet.
	 */
	private void importTags(RepositoryCollection source)
	{
		Repository tagRepo = source.getRepository(TagMetaData.ENTITY_NAME);
		if (tagRepo != null)
		{
			for (Entity tag : tagRepo)
			{
				Entity transformed = new DefaultEntity(new TagMetaData(), dataService, tag);
				Entity existingTag = dataService
						.findOne(TagMetaData.ENTITY_NAME, tag.getString(TagMetaData.IDENTIFIER));

				if (existingTag == null)
				{
					dataService.add(TagMetaData.ENTITY_NAME, transformed);
				}
				else
				{
					dataService.update(TagMetaData.ENTITY_NAME, transformed);
				}
			}
		}
	}

	/**
	 * Drops entities and added attributes and reindexes the entities whose attributes were modified.
	 */
	public void rollbackSchemaChanges(EmxImportJob job)
	{
		LOG.info("Rolling back changes.");
		dropAddedEntities(job.metaDataChanges.getAddedEntities());
		List<String> entities = dropAddedAttributes(job.metaDataChanges.getAddedAttributes());

		// Reindex
		Set<String> entitiesToIndex = Sets.newLinkedHashSet(job.source.getEntityNames());
		entitiesToIndex.addAll(entities);
		entitiesToIndex.add("tags");
		entitiesToIndex.add("packages");
		entitiesToIndex.add("entities");
		entitiesToIndex.add("attributes");

		reindex(entitiesToIndex);
	}

	/**
	 * Reindexes entities
	 * 
	 * @param entitiesToIndex
	 *            Set of entity names
	 */
	private void reindex(Set<String> entitiesToIndex)
	{
		for (String entity : entitiesToIndex)
		{
			if (dataService.hasRepository(entity))
			{
				Repository repo = dataService.getRepository(entity);
				if ((repo != null) && (repo instanceof IndexedRepository))
				{
					((IndexedRepository) repo).rebuildIndex();
				}
			}
		}
	}

	/**
	 * Drops attributes from entities
	 */
	private List<String> dropAddedAttributes(ImmutableMap<String, Collection<AttributeMetaData>> addedAttributes)
	{
		List<String> entities = Lists.newArrayList(addedAttributes.keySet());
		Collections.reverse(entities);

		for (String entityName : entities)
		{
			for (AttributeMetaData attribute : addedAttributes.get(entityName))
			{
				dataService.getMeta().deleteAttribute(entityName, attribute.getName());
			}
		}
		return entities;
	}

	/**
	 * Drops added entities in the reverse order in which they were created.
	 */
	private void dropAddedEntities(List<String> addedEntities)
	{
		// Rollback metadata, create table statements cannot be rolled back, we have to do it ourselves
		Lists.reverse(addedEntities).forEach(dataService.getMeta()::deleteEntityMeta);
	}

	/**
	 * Updates a repository with entities.
	 * 
	 * @param repo
	 *            the {@link Repository} to update
	 * @param entities
	 *            the entities to
	 * @param dbAction
	 *            {@link DatabaseAction} describing how to merge the existing entities
	 * @return number of updated entities
	 */
	public int update(Repository repo, Iterable<? extends Entity> entities, DatabaseAction dbAction)
	{
		if (entities == null) return 0;

		String idAttributeName = repo.getEntityMetaData().getIdAttribute().getName();
		FieldType idDataType = repo.getEntityMetaData().getIdAttribute().getDataType();

		HugeSet<Object> existingIds = new HugeSet<Object>();
		HugeSet<Object> ids = new HugeSet<Object>();
		try
		{
			for (Entity entity : entities)
			{
				Object id = entity.get(idAttributeName);
				if (id != null)
				{
					ids.add(id);
				}
			}

			if (!ids.isEmpty())
			{
				// Check if the ids already exist
				if (repo.count() > 0)
				{
					int batchSize = 100;
					Query q = new QueryImpl();
					Iterator<Object> it = ids.iterator();
					int batchCount = 0;
					while (it.hasNext())
					{
						q.eq(idAttributeName, it.next());
						batchCount++;
						if (batchCount == batchSize || !it.hasNext())
						{
							for (Entity existing : repo.findAll(q))
							{
								existingIds.add(existing.getIdValue());
							}
							q = new QueryImpl();
							batchCount = 0;
						}
						else
						{
							q.or();
						}
					}
				}
			}

			int count = 0;
			switch (dbAction)
			{
				case ADD:
					if (!existingIds.isEmpty())
					{
						StringBuilder msg = new StringBuilder();
						msg.append("Trying to add existing ").append(repo.getName())
								.append(" entities as new insert: ");

						int i = 0;
						Iterator<?> it = existingIds.iterator();
						while (it.hasNext() && i < 5)
						{
							if (i > 0)
							{
								msg.append(",");
							}
							msg.append(it.next());
							i++;
						}

						if (it.hasNext())
						{
							msg.append(" and more.");
						}
						throw new MolgenisDataException(msg.toString());
					}

					count = repo.add(entities);
					break;

				case ADD_UPDATE_EXISTING:
					int batchSize = 1000;
					List<Entity> existingEntities = Lists.newArrayList();
					List<Entity> newEntities = Lists.newArrayList();

					Iterator<? extends Entity> it = entities.iterator();
					while (it.hasNext())
					{
						Entity entity = it.next();
						count++;
						Object id = idDataType.convert(entity.get(idAttributeName));
						if (existingIds.contains(id))
						{
							existingEntities.add(entity);
							if (existingEntities.size() == batchSize)
							{
								repo.update(existingEntities);
								existingEntities.clear();
							}
						}
						else
						{
							newEntities.add(entity);
							if (newEntities.size() == batchSize)
							{
								repo.add(newEntities);
								newEntities.clear();
							}
						}
					}

					if (!existingEntities.isEmpty())
					{
						repo.update(existingEntities);
					}

					if (!newEntities.isEmpty())
					{
						repo.add(newEntities);
					}
					break;

				case UPDATE:
					int errorCount = 0;
					StringBuilder msg = new StringBuilder();
					msg.append("Trying to update not exsisting ").append(repo.getName()).append(" entities:");

					for (Entity entity : entities)
					{
						count++;
						Object id = idDataType.convert(entity.get(idAttributeName));
						if (!existingIds.contains(id))
						{
							if (++errorCount == 6)
							{
								break;
							}

							if (errorCount > 0)
							{
								msg.append(", ");
							}
							msg.append(id);
						}
					}

					if (errorCount > 0)
					{
						if (errorCount == 6)
						{
							msg.append(" and more.");
						}
						throw new MolgenisDataException(msg.toString());
					}
					repo.update(entities);
					break;

				default:
					break;

			}

			return count;
		}
		finally
		{
			IOUtils.closeQuietly(existingIds);
			IOUtils.closeQuietly(ids);
		}
	}

}