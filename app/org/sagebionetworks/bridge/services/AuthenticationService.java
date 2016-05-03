package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sagebionetworks.bridge.dao.ParticipantOption.DATA_GROUPS;
import static org.sagebionetworks.bridge.dao.ParticipantOption.LANGUAGES;
import static org.sagebionetworks.bridge.dao.ParticipantOption.SHARING_SCOPE;

import java.util.Map;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.config.BridgeConfig;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.dao.DistributedLockDao;
import org.sagebionetworks.bridge.dao.ParticipantOption.SharingScope;
import org.sagebionetworks.bridge.dao.StudyConsentDao;
import org.sagebionetworks.bridge.dao.UserConsentDao;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.exceptions.StudyLimitExceededException;
import org.sagebionetworks.bridge.models.CriteriaContext;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.ConsentStatus;
import org.sagebionetworks.bridge.models.accounts.Email;
import org.sagebionetworks.bridge.models.accounts.EmailVerification;
import org.sagebionetworks.bridge.models.accounts.HealthId;
import org.sagebionetworks.bridge.models.accounts.PasswordReset;
import org.sagebionetworks.bridge.models.accounts.SignIn;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.User;
import org.sagebionetworks.bridge.models.accounts.ParticipantOptionsLookup;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.subpopulations.ConsentSignature;
import org.sagebionetworks.bridge.models.subpopulations.SubpopulationGuid;
import org.sagebionetworks.bridge.redis.RedisKey;
import org.sagebionetworks.bridge.validators.EmailValidator;
import org.sagebionetworks.bridge.validators.EmailVerificationValidator;
import org.sagebionetworks.bridge.validators.PasswordResetValidator;
import org.sagebionetworks.bridge.validators.SignInValidator;
import org.sagebionetworks.bridge.validators.StudyParticipantValidator;
import org.sagebionetworks.bridge.validators.Validate;

import org.joda.time.DateTimeUtils;
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
    private UserConsentDao userConsentDao;
    private StudyConsentDao studyConsentDao;
    
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
    @Autowired
    final void setUserConsentService(UserConsentDao userConsentDao) {
        this.userConsentDao = userConsentDao;
    }
    @Autowired
    final void setStudyConsentService(StudyConsentDao studyConsentDao) {
        this.studyConsentDao = studyConsentDao;
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

    public UserSession signIn(Study study, CriteriaContext context, SignIn signIn) throws EntityNotFoundException {
        checkNotNull(study, "Study cannot be null");
        checkNotNull(signIn, "Sign in cannot be null");
        Validate.entityThrowingException(signInValidator, signIn);

        final String signInLock = study.getIdentifier() + RedisKey.SEPARATOR + signIn.getEmail();
        String lockId = null;
        try {
            lockId = lockDao.acquireLock(SignIn.class, signInLock, LOCK_EXPIRE_IN_SECONDS);
            Account account = accountDao.authenticate(study, signIn);
            
            UserSession session = getSessionFromAccount(study, context, account);
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

    public void signUp(Study study, StudyParticipant participant, boolean isAnonSignUp) {
        checkNotNull(study, "Study cannot be null");
        checkNotNull(participant, "Participant cannot be null");
        
        Validate.entityThrowingException(new StudyParticipantValidator(study, true), participant);
        
        String lockId = null;
        try {
            lockId = lockDao.acquireLock(StudyParticipant.class, participant.getEmail(), LOCK_EXPIRE_IN_SECONDS);
            if (studyEnrollmentService.isStudyAtEnrollmentLimit(study)) {
                throw new StudyLimitExceededException(study);
            }
            Account account = accountDao.signUp(study, participant, isAnonSignUp);
            if (!participant.getDataGroups().isEmpty()) {
                final String healthCode = getHealthCode(study, account);
                optionsService.setStringSet(study, healthCode, DATA_GROUPS, participant.getDataGroups());
            }
            
        } catch(EntityAlreadyExistsException e) {
            // Suppress this. Otherwise it the response reveals that the email has already been taken, 
            // and you can infer who is in the study from the response. Instead send a reset password 
            // request to the email address in case user has forgotten password and is trying to sign 
            // up again. Non-anonymous sign ups (sign ups done by admins on behalf of users) still get a 404
            if (isAnonSignUp) {
                Email email = new Email(study.getIdentifier(), participant.getEmail());
                requestResetPassword(study, email);
                logger.info("Sign up attempt for existing email address in study '"+study.getIdentifier()+"'");
            } else {
                throw e;
            }
        } finally {
            lockDao.releaseLock(StudyParticipant.class, participant.getEmail(), lockId);
        }
    }

    public UserSession verifyEmail(Study study, CriteriaContext context, EmailVerification verification) throws ConsentRequiredException {
        checkNotNull(verification, "Verification object cannot be null");

        Validate.entityThrowingException(verificationValidator, verification);
        
        Account account = accountDao.verifyEmail(study, verification);
        UserSession session = getSessionFromAccount(study, context, account);
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
    
    public UserSession updateSession(Study study, CriteriaContext context, String userId) {
        Account account = accountDao.getAccount(study, userId);
        return getSessionFromAccount(study, context, account);
    }
    
    /**
     * Early in the production lifetime of the application, some accounts were created that have a signature 
     * in Stormpath, but no record in DynamoDB. Some other records had a DynamoDB record in an earlier version 
     * of the table that were not successfully migrated to a later version of the table. These folks should 
     * be in the study but are getting 412s (not consented) from the server. We examine their records on sign 
     * in, and if there is a signature and the consent status does not record the user as consented, we 
     * create the DynamoDB record. (The signatures are from Stormpath and the consent status records are 
     * constructed from DynamoDB).
     */
    private void repairConsents(Account account, UserSession session, CriteriaContext context){
        boolean repaired = false;
        
        Map<SubpopulationGuid,ConsentStatus> statuses = session.getUser().getConsentStatuses();
        for (Map.Entry<SubpopulationGuid,ConsentStatus> entry : statuses.entrySet()) {
            ConsentSignature activeSignature = account.getActiveConsentSignature(entry.getKey());
            ConsentStatus status = entry.getValue();

            if (activeSignature != null && !status.isConsented()) {
                repairConsent(session, entry.getKey(), activeSignature);
                repaired = true;
            }
        }
        
        // These are incorrect since they are based on looking up DDB records, so re-create them.
        if (repaired) {
            session.getUser().setConsentStatuses(consentService.getConsentStatuses(context));
        }
    }
    
    /**
     * If the signature exists but consentStatus says the user is not consented, then the record is missing in
     * DDB. We need to create it.
     */
    private void repairConsent(UserSession session, SubpopulationGuid subpopGuid, ConsentSignature activeSignature) {
        logger.info("Signature found without a matching user consent record. Adding consent for " + session.getUser().getId());
        long signedOn = activeSignature.getSignedOn();
        long consentCreatedOn = studyConsentDao.getActiveConsent(subpopGuid).getCreatedOn(); 
        userConsentDao.giveConsent(session.getUser().getHealthCode(), subpopGuid, consentCreatedOn, signedOn);
    }
    
    private UserSession getSessionFromAccount(Study study, CriteriaContext context, Account account) {
        final UserSession session = getSession(account);
        session.setAuthenticated(true);
        session.setEnvironment(config.getEnvironment());
        session.setStudyIdentifier(study.getStudyIdentifier());

        final User user = new User(account);
        user.setStudyKey(study.getIdentifier());

        final String healthCode = getHealthCode(study, account);
        user.setHealthCode(healthCode);
        
        ParticipantOptionsLookup lookup = optionsService.getOptions(healthCode);
        user.setSharingScope(lookup.getEnum(SHARING_SCOPE, SharingScope.class));
        user.setDataGroups(lookup.getStringSet(DATA_GROUPS));
        user.setLanguages(lookup.getOrderedStringSet(LANGUAGES));

        // If the user does not have a language persisted yet, now that we have a session, we can retrieve it 
        // from the context, add it to the user/session, and persist it.
        if (user.getLanguages().isEmpty() && !context.getLanguages().isEmpty()) {
            user.setLanguages(context.getLanguages());
            optionsService.setOrderedStringSet(study, healthCode, LANGUAGES, context.getLanguages());
        }
        
        CriteriaContext newContext = new CriteriaContext.Builder()
                .withContext(context)
                .withLanguages(user.getLanguages())
                .withHealthCode(user.getHealthCode())
                .withUserDataGroups(user.getDataGroups())
                .build();

        user.setConsentStatuses(consentService.getConsentStatuses(newContext));
        session.setUser(user);
        
        repairConsents(account, session, newContext);
        
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
