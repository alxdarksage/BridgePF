package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.util.List;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.dao.SchedulePlanDao;
import org.sagebionetworks.bridge.models.ClientInfo;
import org.sagebionetworks.bridge.models.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.models.GuidCreatedOnVersionHolderImpl;
import org.sagebionetworks.bridge.models.schedules.Activity;
import org.sagebionetworks.bridge.models.schedules.Schedule;
import org.sagebionetworks.bridge.models.schedules.SchedulePlan;
import org.sagebionetworks.bridge.models.schedules.SurveyReference;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.surveys.Survey;
import org.sagebionetworks.bridge.validators.SchedulePlanValidator;
import org.sagebionetworks.bridge.validators.Validate;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


@Component
public class SchedulePlanService {
    
    private SchedulePlanDao schedulePlanDao;
    private SurveyService surveyService;

    @Autowired
    public final void setSchedulePlanDao(SchedulePlanDao schedulePlanDao) {
        this.schedulePlanDao = schedulePlanDao;
    }
    @Autowired
    public final void setSurveyService(SurveyService surveyService) {
        this.surveyService = surveyService;
    }

    public List<SchedulePlan> getSchedulePlans(ClientInfo clientInfo, StudyIdentifier studyIdentifier) {
        return schedulePlanDao.getSchedulePlans(clientInfo, studyIdentifier);
    }

    public SchedulePlan getSchedulePlan(StudyIdentifier studyIdentifier, String guid) {
        return schedulePlanDao.getSchedulePlan(studyIdentifier, guid);
    }

    public SchedulePlan createSchedulePlan(Study study, SchedulePlan plan) {
        checkNotNull(study);
        checkNotNull(plan);

        // Plan must always be in user's study, replace all the GUIDs if they exist.
        plan.setStudyKey(study.getIdentifier());
        updateGuids(plan, true);
        
        SchedulePlanValidator validator = new SchedulePlanValidator(study.getDataGroups(), study.getTaskIdentifiers());
        Validate.entityThrowingException(validator, plan);

        StudyIdentifier studyId = study.getStudyIdentifier();
        lookupSurveyReferenceIdentifiers(studyId, plan);
        return schedulePlanDao.createSchedulePlan(studyId, plan);
    }
    
    public SchedulePlan updateSchedulePlan(Study study, SchedulePlan plan) {
        checkNotNull(study);
        checkNotNull(plan);
        
        // Plan must always be in user's study, any missing GUIDs need to be added.
        plan.setStudyKey(study.getIdentifier());
        updateGuids(plan, false);
        
        SchedulePlanValidator validator = new SchedulePlanValidator(study.getDataGroups(), study.getTaskIdentifiers());
        Validate.entityThrowingException(validator, plan);
        
        StudyIdentifier studyId = study.getStudyIdentifier();
        lookupSurveyReferenceIdentifiers(studyId, plan);
        return schedulePlanDao.updateSchedulePlan(studyId, plan);
    }

    public void deleteSchedulePlan(StudyIdentifier studyIdentifier, String guid) {
        checkNotNull(studyIdentifier);
        checkNotNull(isNotBlank(guid));
        
        schedulePlanDao.deleteSchedulePlan(studyIdentifier, guid);
    }
    
    /**
     * Ensure that elements have assigned GUIDs. If forced, everything is given a new GUID (and it's 
     * effectively cloned). If not forced, only elements missing a GUID are assigned new GUIDs.
     */
    private void updateGuids(SchedulePlan plan, boolean forceNew) {
        if (forceNew) {
            plan.setVersion(null);
            plan.setGuid(BridgeUtils.generateGuid());
        }
        for (Schedule schedule : plan.getStrategy().getAllPossibleSchedules()) {
            for (int i=0; i < schedule.getActivities().size(); i++) {
                Activity activity = schedule.getActivities().get(i);
                if (forceNew || StringUtils.isBlank(activity.getGuid())) {
                    Activity newActivity = new Activity.Builder().withActivity(activity)
                            .withGuid(BridgeUtils.generateGuid()).build();
                    schedule.getActivities().set(i, newActivity);
                }
            }
        }
    }    
    
    /**
     * If the activity has a survey reference, look up the survey's identifier. Don't trust the client to 
     * supply the correct one for the survey's primary keys. We're adding this when writing schedules because 
     * the clients have come to depend on it, and this is more efficient than looking it up on every read.
     * 
     * @param studyId
     * @param activity
     * @return
     */
    private void lookupSurveyReferenceIdentifiers(StudyIdentifier studyId, SchedulePlan plan) {
        for (Schedule schedule : plan.getStrategy().getAllPossibleSchedules()) {
            for (int i=0; i < schedule.getActivities().size(); i++) {
                Activity activity = schedule.getActivities().get(i);
                activity = updateActivityWithSurveyIdentifier(studyId, activity);
                schedule.getActivities().set(i, activity);
            }
        }
    }

    private Activity updateActivityWithSurveyIdentifier(StudyIdentifier studyId, Activity activity) {
        if (activity.getSurvey() != null) {
            SurveyReference ref = activity.getSurvey();
            
            if (ref.getCreatedOn() == null) { // pointer to most recently published survey
                Survey survey = surveyService.getSurveyMostRecentlyPublishedVersion(studyId, ref.getGuid());
                return new Activity.Builder().withActivity(activity)
                        .withPublishedSurvey(survey.getIdentifier(), survey.getGuid()).build();
            } else {
                GuidCreatedOnVersionHolder keys = new GuidCreatedOnVersionHolderImpl(ref.getGuid(), ref.getCreatedOn().getMillis());
                Survey survey = surveyService.getSurvey(keys);
                return new Activity.Builder().withActivity(activity)
                        .withSurvey(survey.getIdentifier(), ref.getGuid(), ref.getCreatedOn()).build();
            }
        }
        return activity;
    }
}
