package org.molgenis.security.account;

import static org.molgenis.security.user.UserAccountController.MIN_PASSWORD_LENGTH;

import java.net.URI;
import java.util.Collections;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import org.apache.log4j.Logger;
import org.molgenis.framework.db.DatabaseAccessException;
import org.molgenis.framework.db.DatabaseException;
import org.molgenis.omx.auth.MolgenisUser;
import org.molgenis.security.captcha.CaptchaException;
import org.molgenis.security.captcha.CaptchaRequest;
import org.molgenis.security.captcha.CaptchaService;
import org.molgenis.security.user.MolgenisUserException;
import org.molgenis.util.CountryCodes;
import org.molgenis.util.ErrorMessageResponse;
import org.molgenis.util.ErrorMessageResponse.ErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
@RequestMapping("/account")
public class AccountController
{
	private static final Logger logger = Logger.getLogger(AccountController.class);

	@Autowired
	private AccountService accountService;

	@Autowired
	private CaptchaService captchaService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@RequestMapping(value = "/login", method = RequestMethod.GET)
	public String getLoginForm()
	{
		return "login-modal";
	}

	@RequestMapping(value = "/register", method = RequestMethod.GET)
	public ModelAndView getRegisterForm() throws DatabaseException
	{
		ModelAndView model = new ModelAndView("register-modal");
		model.addObject("countries", CountryCodes.get());
		model.addObject("min_password_length", MIN_PASSWORD_LENGTH);
		return model;
	}

	@RequestMapping(value = "/password/reset", method = RequestMethod.GET)
	public String getPasswordResetForm()
	{
		return "resetpassword-modal";
	}

	// Spring's FormHttpMessageConverter cannot bind target classes (as ModelAttribute can)
	@RequestMapping(value = "/register", method = RequestMethod.POST, headers = "Content-Type=application/x-www-form-urlencoded")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void registerUser(@Valid @ModelAttribute RegisterRequest registerRequest,
			@Valid @ModelAttribute CaptchaRequest captchaRequest) throws DatabaseException, CaptchaException,
			BindException
	{
		if (!captchaService.validateCaptcha(captchaRequest.getCaptcha()))
		{
			throw new CaptchaException("invalid captcha answer");
		}
		if (!registerRequest.getPassword().equals(registerRequest.getConfirmPassword()))
		{
			throw new BindException(RegisterRequest.class, "password does not match confirm password");
		}
		MolgenisUser molgenisUser = toMolgenisUser(registerRequest);
		URI activationUri = ServletUriComponentsBuilder.fromCurrentContextPath().path("/account/activate").build()
				.toUri();
		accountService.createUser(molgenisUser, activationUri);
	}

	@RequestMapping(value = "/activate/{activationCode}", method = RequestMethod.GET)
	public String activateUser(@Valid @NotNull @PathVariable String activationCode, Model model)
			throws DatabaseException
	{
		try
		{
			accountService.activateUser(activationCode);
			model.addAttribute("successMessage", "Your account has been activated, you can now sign in.");
		}
		catch (DatabaseException e)
		{
			model.addAttribute("errorMessage", e.getMessage());
		}
		catch (RuntimeException e)
		{
			model.addAttribute("errorMessage", e.getMessage());
		}
		return "forward:/";
	}

	// Spring's FormHttpMessageConverter cannot bind target classes (as ModelAttribute can)
	@RequestMapping(value = "/password/reset", method = RequestMethod.POST, headers = "Content-Type=application/x-www-form-urlencoded")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void resetPassword(@Valid @ModelAttribute PasswordResetRequest passwordResetRequest)
			throws DatabaseException
	{
		accountService.resetPassword(passwordResetRequest.getEmail());
	}

	@ExceptionHandler(DatabaseAccessException.class)
	@ResponseStatus(value = HttpStatus.UNAUTHORIZED)
	private void handleDatabaseAccessException(DatabaseAccessException e)
	{
	}

	@ExceptionHandler(CaptchaException.class)
	@ResponseStatus(value = HttpStatus.BAD_REQUEST)
	private void handleCaptchaException(CaptchaException e)
	{
	}

	@ExceptionHandler(MolgenisUserException.class)
	@ResponseStatus(value = HttpStatus.BAD_REQUEST)
	@ResponseBody
	public ErrorMessageResponse handleMolgenisUserException(MolgenisUserException e)
	{
		logger.debug("", e);
		return new ErrorMessageResponse(Collections.singletonList(new ErrorMessage(e.getMessage())));
	}

	@ExceptionHandler(RuntimeException.class)
	@ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
	@ResponseBody
	public ErrorMessageResponse handleRuntimeException(RuntimeException e)
	{
		logger.error("", e);
		return new ErrorMessageResponse(Collections.singletonList(new ErrorMessage(e.getMessage())));
	}

	private MolgenisUser toMolgenisUser(RegisterRequest request)
	{
		MolgenisUser user = new MolgenisUser();
		user.setUsername(request.getUsername());
		user.setPassword(passwordEncoder.encode(request.getPassword()));
		user.setEmail(request.getEmail());
		user.setPhone(request.getPhone());
		user.setFax(request.getFax());
		user.setTollFreePhone(request.getTollFreePhone());
		user.setAddress(request.getAddress());
		user.setTitle(request.getTitle());
		user.setLastName(request.getLastname());
		user.setFirstName(request.getFirstname());
		user.setDepartment(request.getDepartment());
		user.setCity(request.getCity());
		user.setCountry(CountryCodes.get(request.getCountry()));
		return user;
	}
}