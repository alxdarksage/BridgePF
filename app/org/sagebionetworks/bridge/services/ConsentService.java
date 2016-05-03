package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.sagebionetworks.bridge.dao.ParticipantOption.EXTERNAL_IDENTIFIER;
import static org.sagebionetworks.bridge.dao.ParticipantOption.SHARING_SCOPE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.apache.commons.io.IOUtils;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.dao.ParticipantOption.SharingScope;
import org.sagebionetworks.bridge.dao.UserConsentDao;
import org.sagebionetworks.bridge.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.exceptions.StudyLimitExceededException;
import org.sagebionetworks.bridge.models.CriteriaContext;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.ConsentStatus;
import org.sagebionetworks.bridge.models.accounts.User;
import org.sagebionetworks.bridge.models.accounts.UserConsent;
import org.sagebionetworks.bridge.models.accounts.UserConsentHistory;
import org.sagebionetworks.bridge.models.accounts.Withdrawal;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.subpopulations.ConsentSignature;
import org.sagebionetworks.bridge.models.subpopulations.StudyConsentView;
import org.sagebionetworks.bridge.models.subpopulations.Subpopulation;
import org.sagebionetworks.bridge.models.subpopulations.SubpopulationGuid;
import org.sagebionetworks.bridge.services.email.ConsentEmailProvider;
import org.sagebionetworks.bridge.services.email.MimeTypeEmailProvider;
import org.sagebionetworks.bridge.services.email.WithdrawConsentEmailProvider;
import org.sagebionetworks.bridge.util.BridgeCollectors;
import org.sagebionetworks.bridge.validators.ConsentAgeValidator;
import org.sagebionetworks.bridge.validators.Validate;

import com.google.common.collect.ImmutableMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConsentService {

    private AccountDao accountDao;
    private ParticipantOptionsService optionsService;
    private SendMailService sendMailService;
    private StudyConsentService studyConsentService;
    private StudyEnrollmentService studyEnrollmentService;
    private UserConsentDao userConsentDao;
    private ActivityEventService activityEventService;
    private SubpopulationService subpopService;
    
    private String consentTemplate;
    
    @Value("classpath:study-defaults/consent-page.xhtml")
    final void setConsentTemplate(org.springframework.core.io.Resource resource) throws IOException {
        this.consentTemplate = IOUtils.toString(resource.getInputStream(), StandardCharsets.UTF_8);
    }
    @Resource(name="stormpathAccountDao")
    final void setAccountDao(AccountDao accountDao) {
        this.accountDao = accountDao;
    }
    @Autowired
    final void setOptionsService(ParticipantOptionsService optionsService) {
        this.optionsService = optionsService;
    }
    @Autowired
    final void setSendMailService(SendMailService sendMailService) {
        this.sendMailService = sendMailService;
    }
    @Autowired
    final void setStudyConsentService(StudyConsentService studyConsentService) {
        this.studyConsentService = studyConsentService;
    }
    @Autowired
    final void setUserConsentDao(UserConsentDao userConsentDao) {
        this.userConsentDao = userConsentDao;
    }
    @Autowired
    final void setActivityEventService(ActivityEventService activityEventService) {
        this.activityEventService = activityEventService;
    }
    @Autowired
    final void setStudyEnrollmentService(StudyEnrollmentService studyEnrollmentService) {
        this.studyEnrollmentService = studyEnrollmentService;
    }
    @Autowired
    final void setSubpopulationService(SubpopulationService subpopService) {
        this.subpopService = subpopService;
    };
    
    /**
     * Get the user's active consent signature (a signature that has not been withdrawn).
     * @param study
     * @param subpopGuid
     * @param user
     * @return
     * @throws EntityNotFoundException if no consent exists
     */
    public ConsentSignature getConsentSignature(Study study, SubpopulationGuid subpopGuid, User user) {
        checkNotNull(study);
        checkNotNull(subpopGuid);
        checkNotNull(user);
        
        // This will throw an EntityNotFoundException if the subpopulation is not in the user's study
        subpopService.getSubpopulation(study, subpopGuid);
        
        Account account = accountDao.getAccount(study, user.getId());
        ConsentSignature signature = account.getActiveConsentSignature(subpopGuid);
        if (signature == null) {
            throw new EntityNotFoundException(ConsentSignature.class);    
        }
        return signature;
    }
    
    /**
     * Consent this user to research. User will be updated to reflect consent.
     * @param study
     * @param subpopGuid
     * @param user
     * @param consentSignature
     * @param sharingScope
     * @param sendEmail
     * @return
     * @throws EntityAlreadyExistsException
     *      if the user already has an active consent to participate in research
     * @throws StudyLimitExceededException
     *      if enrolling the user would exceed the study enrollment limit
     */
    public User consentToResearch(Study study, SubpopulationGuid subpopGuid, User user, ConsentSignature consentSignature, 
            SharingScope sharingScope, boolean sendEmail) {
        checkNotNull(study, Validate.CANNOT_BE_NULL, "study");
        checkNotNull(subpopGuid, Validate.CANNOT_BE_NULL, "subpopulationGuid");
        checkNotNull(user, Validate.CANNOT_BE_NULL, "user");
        checkNotNull(consentSignature, Validate.CANNOT_BE_NULL, "consentSignature");
        checkNotNull(sharingScope, Validate.CANNOT_BE_NULL, "sharingScope");

        ConsentStatus status = user.getConsentStatuses().get(subpopGuid);
        // There will be a status object for each subpopulation the user is mapped to. If 
        // there's no status object, then in effect the subpopulation does not exist for 
        // this user and they should get back a 404.
        if (status == null) {
            throw new EntityNotFoundException(Subpopulation.class);
        }
        if (status != null && status.isConsented()) {
            throw new EntityAlreadyExistsException(consentSignature);
        }
        ConsentAgeValidator validator = new ConsentAgeValidator(study);
        Validate.entityThrowingException(validator, consentSignature);

        StudyConsentView studyConsent = studyConsentService.getActiveConsent(subpopGuid);
        Account account = accountDao.getAccount(study, user.getId());

        // Throws exception if we have exceeded enrollment limit.
        if (studyEnrollmentService.isStudyAtEnrollmentLimit(study)) {
            throw new StudyLimitExceededException(study);
        }
        
        account.getConsentSignatureHistory(subpopGuid).add(consentSignature);
        accountDao.updateAccount(study, account);
        
        UserConsent userConsent = null;
        try {
            userConsent = userConsentDao.giveConsent(user.getHealthCode(), subpopGuid, studyConsent.getCreatedOn(),
                    consentSignature.getSignedOn());
        } catch (Throwable e) {
            int len = account.getConsentSignatureHistory(subpopGuid).size();
            account.getConsentSignatureHistory(subpopGuid).remove(len-1);
            accountDao.updateAccount(study, account);
            throw e;
        }
        // Save supplemental records, fire events, etc.
        if (userConsent != null){
            activityEventService.publishEnrollmentEvent(user.getHealthCode(), userConsent);
        }
        optionsService.setEnum(study, user.getHealthCode(), SHARING_SCOPE, sharingScope);
        
        updateSessionConsentStatuses(user, subpopGuid, true);
        studyEnrollmentService.incrementStudyEnrollment(study, user);
        
        if (sendEmail) {
            MimeTypeEmailProvider consentEmail = new ConsentEmailProvider(study, subpopGuid, user, 
                consentSignature, sharingScope, studyConsentService, consentTemplate);

            sendMailService.sendEmail(consentEmail);
        }
        return user;
    }

    /**
     * Get all the consent status objects for this user. From these, we determine if the user 
     * has consented to the right consents to have access to the study, whether or not those 
     * consents are up-to-date, etc.
     * @param context
     * @return
     */
    public Map<SubpopulationGuid,ConsentStatus> getConsentStatuses(CriteriaContext context) {
        checkNotNull(context);
        checkNotNull(context.getHealthCode());
        
        ImmutableMap.Builder<SubpopulationGuid, ConsentStatus> builder = new ImmutableMap.Builder<>();
        for (Subpopulation subpop : subpopService.getSubpopulationForUser(context)) {
            boolean consented = userConsentDao.hasConsented(context.getHealthCode(), subpop.getGuid());
            boolean mostRecent = hasUserSignedActiveConsent(context.getHealthCode(), subpop.getGuid());
            ConsentStatus status = new ConsentStatus.Builder().withName(subpop.getName())
                    .withGuid(subpop.getGuid()).withRequired(subpop.isRequired())
                    .withConsented(consented).withSignedMostRecentConsent(mostRecent)
                    .build();
            builder.put(subpop.getGuid(), status);
        }
        return builder.build();
    }

    /**
     * Withdraw consent in this study. The withdrawal date is recorded and the user can no longer 
     * access any APIs that require consent, although the user's account (along with the history of 
     * the user's participation) will not be delted.
     * @param study 
     * @param subpopGuid
     * @param user
     * @param withdrawal
     * @param withdrewOn
     */
    public void withdrawConsent(Study study, SubpopulationGuid subpopGuid, User user, Withdrawal withdrawal, long withdrewOn) {
        checkNotNull(study);
        checkNotNull(subpopGuid);
        checkNotNull(user);
        checkNotNull(withdrawal);
        checkArgument(withdrewOn > 0);
        
        Account account = accountDao.getAccount(study, user.getId());
        
        ConsentSignature active = account.getActiveConsentSignature(subpopGuid);
        if (active == null) {
            throw new EntityNotFoundException(ConsentSignature.class);
        }
        ConsentSignature withdrawn = new ConsentSignature.Builder()
            .withConsentSignature(active)
            .withWithdrewOn(withdrewOn).build();
        int index = account.getConsentSignatureHistory(subpopGuid).indexOf(active); // should be length-1
        
        account.getConsentSignatureHistory(subpopGuid).set(index, withdrawn);
        accountDao.updateAccount(study, account);

        try {
            userConsentDao.withdrawConsent(user.getHealthCode(), subpopGuid, withdrewOn);    
        } catch(Exception e) {
            // Could not record the consent, compensate and rethrow the exception
            account.getConsentSignatureHistory(subpopGuid).set(index, active);
            accountDao.updateAccount(study, account);
            throw e;
        }
        
        // Update the user's consent status
        updateSessionConsentStatuses(user, subpopGuid, false);
        
        // Only turn of sharing if the upshot of your change in consents is that you are not consented.
        if (!user.doesConsent()) {
            optionsService.setEnum(study, user.getHealthCode(), SHARING_SCOPE, SharingScope.NO_SHARING);
            user.setSharingScope(SharingScope.NO_SHARING);
        }
        studyEnrollmentService.decrementStudyEnrollment(study, user);
        
        String externalId = optionsService.getOptions(user.getHealthCode())
                .getString(EXTERNAL_IDENTIFIER);
        MimeTypeEmailProvider consentEmail = new WithdrawConsentEmailProvider(study, externalId, user, withdrawal, withdrewOn);
        sendMailService.sendEmail(consentEmail);
    }
    
    /**
     * Get a history of all consent records, whether withdrawn or not, including information from the 
     * consent signature and user consent records. The information is sufficient to identify the 
     * consent that exists for a healthCode, and to retrieve the version of the consent the participant 
     * signed to join the study.
     * @param study
     * @param subpopGuid
     * @param healthCode
     * @param email
     */
    public List<UserConsentHistory> getUserConsentHistory(Study study, SubpopulationGuid subpopGuid, String healthCode, String id) {
        Account account = accountDao.getAccount(study, id);
        boolean hasSignedActiveConsent = hasUserSignedActiveConsent(healthCode, subpopGuid);

        return account.getConsentSignatureHistory(subpopGuid).stream().map(signature -> {
            UserConsentHistory.Builder builder = new UserConsentHistory.Builder();
            builder.withName(signature.getName())
                .withSubpopulationGuid(subpopGuid)
                .withBirthdate(signature.getBirthdate())
                .withImageData(signature.getImageData())
                .withWithdrewOn(signature.getWithdrewOn())
                .withImageMimeType(signature.getImageMimeType())
                .withSignedOn(signature.getSignedOn())
                .withHealthCode(healthCode)
                .withHasSignedActiveConsent(hasSignedActiveConsent);
            try {
                UserConsent consent = userConsentDao.getUserConsent(healthCode, subpopGuid, signature.getSignedOn());
                builder.withWithdrewOn(consent.getWithdrewOn());
                builder.withConsentCreatedOn(consent.getConsentCreatedOn());
            } catch(EntityNotFoundException e) {
                // Rarely (100 records out of 18000 in mPower), The Stormpath JSON does not have a signedOn property,
                // but there is a DDB record. This query doesn't find it because the ConsentSignature assigns a timestamp 
                // that does not match a DDB record. Retrieve the full set of records because in every case examined, 
                // there's one record in the history and that's the record we're looking for.
                List<UserConsent> history = userConsentDao.getUserConsentHistory(healthCode, subpopGuid);
                if (history.size() == 1) {
                    builder.withWithdrewOn(history.get(0).getWithdrewOn());
                    builder.withConsentCreatedOn(history.get(0).getConsentCreatedOn());
                }
            }
            return builder.build();
        }).collect(BridgeCollectors.toImmutableList());
    }
    
    /**
     * Delete all consent records, withdrawn or active, in the process of deleting a user account. This is 
     * used for tests, do not call this method to withdraw a user from a study, or we will not have auditable 
     * records about their participation.
     * @param study
     * @param healthCode
     */
    public void deleteAllConsentsForUser(Study study, String healthCode) {
        checkNotNull(study);
        checkNotNull(healthCode);
        
        // May exceed the subpopulations the user matches, but that's okay
        List<Subpopulation> subpopGuids = subpopService.getSubpopulations(study);
        for (Subpopulation subpop : subpopGuids) {
            userConsentDao.deleteAllConsents(healthCode, subpop.getGuid());    
        }
    }
    
    /**
     * Email the participant's signed consent agreement to the user's email address.
     * @param study
     * @param subpopGuid
     * @param user
     */
    public void emailConsentAgreement(Study study, SubpopulationGuid subpopGuid, User user) {
        checkNotNull(study);
        checkNotNull(subpopGuid);
        checkNotNull(user);

        final ConsentSignature consentSignature = getConsentSignature(study, subpopGuid, user);
        if (consentSignature == null) {
            throw new EntityNotFoundException(ConsentSignature.class);
        }
        final SharingScope sharingScope = optionsService.getOptions(user.getHealthCode()).getEnum(SHARING_SCOPE, SharingScope.class);
        
        MimeTypeEmailProvider consentEmail = new ConsentEmailProvider(
            study, subpopGuid, user, consentSignature, sharingScope, studyConsentService, consentTemplate);
        sendMailService.sendEmail(consentEmail);
    }
    
    private boolean hasUserSignedActiveConsent(String healthCode, SubpopulationGuid subpopGuid) {
        checkNotNull(healthCode);
        checkNotNull(subpopGuid);
        
        UserConsent userConsent = userConsentDao.getActiveUserConsent(healthCode, subpopGuid);
        StudyConsentView mostRecentConsent = studyConsentService.getActiveConsent(subpopGuid);
        
        if (mostRecentConsent != null && userConsent != null) {
            return userConsent.getConsentCreatedOn() == mostRecentConsent.getCreatedOn();
        } else {
            return false;
        }
    }
    
    private void updateSessionConsentStatuses(User user, SubpopulationGuid subpopGuid, boolean consented) {
        if (user.getConsentStatuses() != null) {
            ImmutableMap.Builder<SubpopulationGuid, ConsentStatus> builder = new ImmutableMap.Builder<>();
            for (Map.Entry<SubpopulationGuid,ConsentStatus> entry : user.getConsentStatuses().entrySet()) {
                if (entry.getKey().equals(subpopGuid)) {
                    ConsentStatus updatedStatus = new ConsentStatus.Builder().withConsentStatus(entry.getValue())
                            .withConsented(consented).withSignedMostRecentConsent(consented).build();
                    builder.put(subpopGuid, updatedStatus);
                } else {
                    builder.put(entry.getKey(), entry.getValue());
                }
            }
            user.setConsentStatuses(builder.build());
        }
    }
}
