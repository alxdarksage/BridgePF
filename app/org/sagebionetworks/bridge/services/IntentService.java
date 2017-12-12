package org.sagebionetworks.bridge.services;

import java.util.List;
import java.util.Map;

import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.models.OperatingSystem;
import org.sagebionetworks.bridge.models.accounts.Phone;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.itp.IntentToParticipate;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.subpopulations.Subpopulation;
import org.sagebionetworks.bridge.models.subpopulations.SubpopulationGuid;
import org.sagebionetworks.bridge.validators.IntentToParticipateValidator;
import org.sagebionetworks.bridge.validators.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.common.collect.Iterables;

@Component
public class IntentService {

    /** Hold on to the intent for 4 hours. */
    private static final int EXPIRATION_IN_SECONDS = 4 * 60 * 60;
    
    private StudyService studyService;
    
    private SubpopulationService subpopService;
    
    private ConsentService consentService;
    
    private CacheProvider cacheProvider;
    
    private NotificationsService notificationsService;
    
    @Autowired
    final void setStudyService(StudyService studyService) {
        this.studyService = studyService;
    }
    
    @Autowired
    final void setSubpopulationService(SubpopulationService subpopService) {
        this.subpopService = subpopService;
    }
    
    @Autowired
    final void setConsentService(ConsentService consentService) {
        this.consentService = consentService;
    }
    
    @Autowired
    final void setCacheProvider(CacheProvider cacheProvider) {
        this.cacheProvider = cacheProvider;
    }
    
    @Autowired
    final void setNotificationsService(NotificationsService notificationsService) {
        this.notificationsService = notificationsService;
    }
    
    public void submitIntentToParticipate(IntentToParticipate intent) {
        Validate.entityThrowingException(IntentToParticipateValidator.INSTANCE, intent);
        
        // TODO: if the account exists, we should return. 
        
        // validate study exists
        Study study = studyService.getStudy(intent.getStudy());

        // validate subpopulation exists
        SubpopulationGuid guid = SubpopulationGuid.create(intent.getSubpopGuid());
        subpopService.getSubpopulation(study, guid);
        
        // validate it has not yet been submitted
        String cacheKey = getCacheKey(study, guid, intent.getPhone());

        if (cacheProvider.getObject(cacheKey, IntentToParticipate.class) == null) {
            cacheProvider.setObject(cacheKey, intent, EXPIRATION_IN_SECONDS);
            
            // send an app store link to download the app, if we have something to send.
            if (!study.getInstallLinks().isEmpty()) {
                String message = getInstallLink(intent.getOsName(), study.getInstallLinks());
                notificationsService.sendSMSMessage(study, intent.getPhone(), message);
            }
        }
    }
    
    public void registerIntentToParticipate(Study study, StudyParticipant participant) {
        Phone phone = participant.getPhone();
        // Somehow, this is being called but the user has no phone number.
        if (phone == null) {
            return;
        }
        
        List<Subpopulation> subpops = subpopService.getSubpopulations(study.getStudyIdentifier());
        for (Subpopulation subpop : subpops) {
            String cacheKey = getCacheKey(study, subpop.getGuid(), phone);
            IntentToParticipate intent = cacheProvider.getObject(cacheKey, IntentToParticipate.class);
            if (intent != null) {
                consentService.consentToResearch(study, subpop.getGuid(), participant, 
                        intent.getConsentSignature(), intent.getScope(), true);
                cacheProvider.removeObject(cacheKey);
            }
        }
    }
    
    private String getCacheKey(StudyIdentifier studyId, SubpopulationGuid subpopGuid, Phone phone) {
        return subpopGuid.getGuid() + ":" + phone.getNumber() + ":" + studyId.getIdentifier() + ":itp";
    }

    protected String getInstallLink(String osName, Map<String,String> installLinks) {
        String message = installLinks.get(osName);
        // OS name wasn't submitted or it's wrong, use the universal link
        if (message == null) {
            message = installLinks.get(OperatingSystem.UNIVERSAL);
        }
        // Don't have a link named "Universal" so just find ANYTHING
        if (message == null && !installLinks.isEmpty()) {
            message = Iterables.getFirst(installLinks.values(), null);
        }
        return message;
    }
}