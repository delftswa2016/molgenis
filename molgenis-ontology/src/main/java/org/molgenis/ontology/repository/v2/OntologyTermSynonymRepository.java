package org.molgenis.ontology.repository.v2;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.molgenis.data.Entity;
import org.molgenis.data.EntityMetaData;
import org.molgenis.data.Repository;
import org.molgenis.data.support.MapEntity;
import org.molgenis.data.support.UuidGenerator;
import org.molgenis.ontology.model.OntologyTermSynonymMetaData;
import org.molgenis.ontology.utils.OntologyLoader;
import org.semanticweb.owlapi.model.OWLClass;

public class OntologyTermSynonymRepository implements Repository
{
	private final OntologyLoader ontologyLoader;
	private final UuidGenerator uuidGenerator;
	private final Map<String, Map<String, String>> referenceIds = new HashMap<String, Map<String, String>>();

	public OntologyTermSynonymRepository(OntologyLoader ontologyLoader, UuidGenerator uuidGenerator)
	{
		this.ontologyLoader = ontologyLoader;
		this.uuidGenerator = uuidGenerator;
	}

	@Override
	public Iterator<Entity> iterator()
	{
		return new Iterator<Entity>()
		{
			final Iterator<OWLClass> ontologyTermIterator = ontologyLoader.getAllclasses().iterator();
			private OWLClass currentClass = null;
			private Iterator<String> synonymIterator = null;

			@Override
			public boolean hasNext()
			{
				// OT_1 -> S1, S2
				// OT_2 -> S3, S4
				// OT_3 -> []
				// OT_4 -> []
				// OT_5 -> S5, S6
				while ((currentClass == null || !synonymIterator.hasNext()) && ontologyTermIterator.hasNext())
				{
					currentClass = ontologyTermIterator.next();
					synonymIterator = ontologyLoader.getSynonyms(currentClass).iterator();
				}
				return synonymIterator.hasNext() || ontologyTermIterator.hasNext();
			}

			@Override
			public Entity next()
			{
				String ontologyTermIRI = currentClass.getIRI().toString();
				String synonym = synonymIterator.next();

				MapEntity entity = new MapEntity();

				if (!referenceIds.containsKey(ontologyTermIRI))
				{
					referenceIds.put(ontologyTermIRI, new HashMap<String, String>());
				}

				if (!referenceIds.get(ontologyTermIRI).containsKey(synonym))
				{
					referenceIds.get(ontologyTermIRI).put(synonym, uuidGenerator.generateId());
				}

				entity.set(OntologyTermSynonymMetaData.ID, referenceIds.get(ontologyTermIRI).get(synonym));
				entity.set(OntologyTermSynonymMetaData.ONTOLOGY_TERM_SYNONYM, synonym);
				return entity;
			}
		};
	}

	@Override
	public void close() throws IOException
	{
		// Nothing
	}

	@Override
	public String getName()
	{
		return OntologyTermSynonymMetaData.ENTITY_NAME;
	}

	@Override
	public EntityMetaData getEntityMetaData()
	{
		return OntologyTermSynonymMetaData.getEntityMetaData();
	}

	@Override
	public <E extends Entity> Iterable<E> iterator(Class<E> clazz)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public String getUrl()
	{
		throw new UnsupportedOperationException();
	}

	public Map<String, Map<String, String>> getReferenceIds()
	{
		return referenceIds;
	}
}
