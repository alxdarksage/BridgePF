package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sagebionetworks.bridge.dao.ParticipantOption.DATA_GROUPS;
import static org.sagebionetworks.bridge.dao.ParticipantOption.SHARING_SCOPE;
import static org.sagebionetworks.bridge.dao.ParticipantOption.SharingScope;

import java.util.Map;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.config.BridgeConfig;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.dao.DistributedLockDao;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.exceptions.StudyLimitExceededException;
import org.sagebionetworks.bridge.models.ClientInfo;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.ConsentStatus;
import org.sagebionetworks.bridge.models.accounts.Email;
import org.sagebionetworks.bridge.models.accounts.EmailVerification;
import org.sagebionetworks.bridge.models.accounts.HealthId;
import org.sagebionetworks.bridge.models.accounts.PasswordReset;
import org.sagebionetworks.bridge.models.accounts.SignIn;
import org.sagebionetworks.bridge.models.accounts.SignUp;
import org.sagebionetworks.bridge.models.accounts.User;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.schedules.ScheduleContext;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.subpopulations.SubpopulationGuid;
import org.sagebionetworks.bridge.redis.RedisKey;
import org.sagebionetworks.bridge.validators.EmailValidator;
import org.sagebionetworks.bridge.validators.EmailVerificationValidator;
import org.sagebionetworks.bridge.validators.PasswordResetValidator;
import org.sagebionetworks.bridge.validators.SignInValidator;
import org.sagebionetworks.bridge.validators.SignUpValidator;
import org.sagebionetworks.bridge.validators.Validate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("authenticationService")
public class AuthenticationService {
    
    private final Logger logger = LoggerFactory.getLogger(AuthenticationService.class);

    private final int LOCK_EXPIRE_IN_SECONDS = 5;

    private DistributedLockDao lockDao;
    private CacheProvider cacheProvider;
    private BridgeConfig config;
    private ConsentService consentService;
    private ParticipantOptionsService optionsService;
    private AccountDao accountDao;
    private HealthCodeService healthCodeService;
    private StudyEnrollmentService studyEnrollmentService;
    
    private EmailVerificationValidator verificationValidator;
    private SignInValidator signInValidator;
    private PasswordResetValidator passwordResetValidator;
    private EmailValidator emailValidator;

    @Autowired
    final void setDistributedLockDao(DistributedLockDao lockDao) {
        this.lockDao = lockDao;
    }
    @Autowired
    final void setCacheProvider(CacheProvider cache) {
        this.cacheProvider = cache;
    }
    @Autowired
    final void setBridgeConfig(BridgeConfig config) {
        this.config = config;
    }
    @Autowired
    final void setConsentService(ConsentService consentService) {
        this.consentService = consentService;
    }
    @Autowired
    final void setOptionsService(ParticipantOptionsService optionsService) {
        this.optionsService = optionsService;
    }
    @Autowired
    final void setAccountDao(AccountDao accountDao) {
        this.accountDao = accountDao;
    }
    @Autowired
    final void setHealthCodeService(HealthCodeService healthCodeService) {
        this.healthCodeService = healthCodeService;
    }
    @Autowired
    final void setStudyEnrollmentService(StudyEnrollmentService studyEnrollmentService) {
        this.studyEnrollmentService = studyEnrollmentService;
    }
    @Autowired
    final void setEmailVerificationValidator(EmailVerificationValidator validator) {
        this.verificationValidator = validator;
    }
    @Autowired
    final void setSignInValidator(SignInValidator validator) {
        this.signInValidator = validator;
    }
    @Autowired
    final void setPasswordResetValidator(PasswordResetValidator validator) {
        this.passwordResetValidator = validator;
    }
    @Autowired
    final void setEmailValidator(EmailValidator validator) {
        this.emailValidator = validator;
    }
    
    /**
     * This method returns the cached session for the user. A ScheduleContext object is not provided to the method, 
     * and the user's consent status is not re-calculated based on participation in one more more subpopulations. 
     * This only happens when calling session-constructing service methods (signIn and verifyEmail, both of which 
     * return newly constructed sessions).
     * @param sessionToken
     * @return session
     *      the cached user session calculated on sign in or during verify email workflow
     */
    public UserSession getSession(String sessionToken) {
        if (sessionToken == null) {
            return null;
        }
        return cacheProvider.getUserSession(sessionToken);
    }

    public UserSession signIn(Study study, ClientInfo clientInfo, SignIn signIn) throws EntityNotFoundException {
        checkNotNull(study, "Study cannot be null");
        checkNotNull(signIn, "Sign in cannot be null");
        Validate.entityThrowingException(signInValidator, signIn);

        final String signInLock = study.getIdentifier() + RedisKey.SEPARATOR + signIn.getUsername();
        String lockId = null;
        try {
            lockId = lockDao.acquireLock(SignIn.class, signInLock, LOCK_EXPIRE_IN_SECONDS);
            Account account = accountDao.authenticate(study, signIn);
            
            UserSession session = getSessionFromAccount(study, clientInfo, account);
            cacheProvider.setUserSession(session);
            return session;
        } finally {
            if (lockId != null) {
                lockDao.releaseLock(SignIn.class, signInLock, lockId);
            }
        }
    }

    public void signOut(final UserSession session) {
        if (session != null) {
            cacheProvider.removeSession(session);
        }
    }

    public void signUp(Study study, SignUp signUp, boolean isAnonSignUp) {
        checkNotNull(study, "Study cannot be null");
        checkNotNull(signUp, "Sign up cannot be null");
        
        Validate.entityThrowingException(new SignUpValidator(study.getPasswordPolicy(), study.getDataGroups()), signUp);
        
        String lockId = null;
        try {
            lockId = lockDao.acquireLock(SignUp.class, signUp.getEmail(), LOCK_EXPIRE_IN_SECONDS);
            if (studyEnrollmentService.isStudyAtEnrollmentLimit(study)) {
                throw new StudyLimitExceededException(study);
            }
            Account account = accountDao.signUp(study, signUp, isAnonSignUp);
            if (!signUp.getDataGroups().isEmpty()) {
                final String healthCode = getHealthCode(study, account);
                optionsService.setStringSet(study, healthCode, DATA_GROUPS, signUp.getDataGroups());
            }
            
        } catch(EntityAlreadyExistsException exception) {
            // Not a public sign up, no reason to hide outcome of account creation
            if (!isAnonSignUp) {
                throw exception;
            }
            // Either username or email has been taken. If both, or the email, we return 200 and email a reset password 
            // request to the account holder. This prevents account enumeration compromises. However, if the email 
            // address doesn't exist for this username, then this person is only re-using the username, and we have to 
            // tell them. Otherwise, we return 200, account creation fails, and nothing else happens, which is deeply 
            // confusing. 
            logger.info("Sign up attempt for existing username/email address in study '"+study.getIdentifier()+"', trying to email user");
            try {
                Email email = new Email(study.getIdentifier(), signUp.getEmail());
                accountDao.requestResetPassword(study, email);
                return;
            } catch(EntityNotFoundException noEmailException) {
                logger.info("Email not found, must notify user");
                // But say the user name exists, not the account
                throw new EntityAlreadyExistsException(exception.getEntity(), "Username already exists.");
            }
        } finally {
            lockDao.releaseLock(SignUp.class, signUp.getEmail(), lockId);
        }
    }

    public UserSession verifyEmail(Study study, ClientInfo clientInfo, EmailVerification verification) throws ConsentRequiredException {
        checkNotNull(verification, "Verification object cannot be null");

        Validate.entityThrowingException(verificationValidator, verification);
        
        Account account = accountDao.verifyEmail(study, verification);
        UserSession session = getSessionFromAccount(study, clientInfo, account);
        cacheProvider.setUserSession(session);
        return session;
    }
    
    public void resendEmailVerification(StudyIdentifier studyIdentifier, Email email) {
        checkNotNull(studyIdentifier, "StudyIdentifier object cannnot be null");
        checkNotNull(email, "Email object cannnot be null");
        
        Validate.entityThrowingException(emailValidator, email);
        try {
            accountDao.resendEmailVerificationToken(studyIdentifier, email);    
        } catch(EntityNotFoundException e) {
            // Suppress this. Otherwise it reveals if the account does not exist
            logger.info("Resend email verification for unregistered email in study '"+studyIdentifier.getIdentifier()+"'");
        }
    }

    public void requestResetPassword(Study study, Email email) throws BridgeServiceException {
        checkNotNull(study);
        checkNotNull(email);
        
        Validate.entityThrowingException(emailValidator, email);
        try {
            accountDao.requestResetPassword(study, email);    
        } catch(EntityNotFoundException e) {
            // Suppress this. Otherwise it reveals if the account does not exist
            logger.info("Request reset password request for unregistered email in study '"+study.getIdentifier()+"'");
        }
    }

    public void resetPassword(PasswordReset passwordReset) throws BridgeServiceException {
        checkNotNull(passwordReset, "Password reset object required");

        Validate.entityThrowingException(passwordResetValidator, passwordReset);
        
        accountDao.resetPassword(passwordReset);
    }
    
    private UserSession getSessionFromAccount(Study study, ClientInfo clientInfo, Account account) {
        final UserSession session = getSession(account);
        session.setAuthenticated(true);
        session.setEnvironment(config.getEnvironment());
        session.setStudyIdentifier(study.getStudyIdentifier());

        final User user = new User(account);
        user.setStudyKey(study.getIdentifier());

        final String healthCode = getHealthCode(study, account);
        user.setHealthCode(healthCode);
        
        user.setSharingScope(optionsService.getEnum(healthCode, SHARING_SCOPE, SharingScope.class));
        user.setDataGroups(optionsService.getStringSet(healthCode, DATA_GROUPS));

        // now that we know more about this user, we can expand on the request context.
        ScheduleContext context = new ScheduleContext.Builder()
                .withClientInfo(clientInfo)
                .withHealthCode(healthCode)
                .withUserDataGroups(user.getDataGroups())
                .withStudyIdentifier(study.getIdentifier()) // probably already set
                .build();
        
        Map<SubpopulationGuid,ConsentStatus> statuses = consentService.getConsentStatuses(context);
        
        user.setConsentStatuses(statuses);
        session.setUser(user);
        
        return session;
    }

    private UserSession getSession(final Account account) {
        final UserSession session = cacheProvider.getUserSessionByUserId(account.getId());
        if (session != null) {
            return session;
        }
        final UserSession newSession = new UserSession();
        newSession.setSessionToken(BridgeUtils.generateGuid());
        // Internal session token to identify sessions internally (e.g. in metrics)
        newSession.setInternalSessionToken(BridgeUtils.generateGuid());
        return newSession;
    }

    /**
     * Any user who authenticates has a health ID/code generated and assigned. It happens at authentication 
     * because some users are automatically marked as consented, which means we have these users accessing 
     * all the APIs that expect users to have health codes, which unknown consequences if they don't. We 
     * do not have to do it at sign up or when the user actually consents (interestingly enough). 
     * @param study
     * @param account
     * @return
     */
    private String getHealthCode(Study study, Account account) {
        HealthId healthId = healthCodeService.getMapping(account.getHealthId());
        if (healthId == null) {
            healthId = healthCodeService.createMapping(study);
            account.setHealthId(healthId.getId());
            accountDao.updateAccount(study, account);
            logger.debug("Health ID/code pair created for " + account.getId() + " in study " + study.getName());
        }
        return healthId.getCode();
    }
}
