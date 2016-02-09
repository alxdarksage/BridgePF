package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Comparator.comparing;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.sagebionetworks.bridge.util.BridgeCollectors.toImmutableList;

import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.dao.ScheduledActivityDao;
import org.sagebionetworks.bridge.dao.UserConsentDao;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.models.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.models.GuidCreatedOnVersionHolderImpl;
import org.sagebionetworks.bridge.models.accounts.User;
import org.sagebionetworks.bridge.models.accounts.UserConsent;
import org.sagebionetworks.bridge.models.schedules.Activity;
import org.sagebionetworks.bridge.models.schedules.ActivityType;
import org.sagebionetworks.bridge.models.schedules.Schedule;
import org.sagebionetworks.bridge.models.schedules.ScheduleContext;
import org.sagebionetworks.bridge.models.schedules.SchedulePlan;
import org.sagebionetworks.bridge.models.schedules.ScheduledActivity;
import org.sagebionetworks.bridge.models.schedules.ScheduledActivityStatus;
import org.sagebionetworks.bridge.models.schedules.SurveyReference;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.subpopulations.Subpopulation;
import org.sagebionetworks.bridge.models.surveys.SurveyAnswer;
import org.sagebionetworks.bridge.models.surveys.SurveyResponseView;
import org.sagebionetworks.bridge.validators.ScheduleContextValidator;
import org.sagebionetworks.bridge.validators.Validate;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

@Component
public class ScheduledActivityService {
    
    private static final Logger LOG = LoggerFactory.getLogger(ScheduledActivityService.class);
    
    private static final ScheduleContextValidator VALIDATOR = new ScheduleContextValidator();
    private static final List<SurveyAnswer> EMPTY_ANSWERS = ImmutableList.of();
    
    private ScheduledActivityDao activityDao;
    
    private ActivityEventService activityEventService;
    
    private SchedulePlanService schedulePlanService;
    
    private UserConsentDao userConsentDao;
    
    private SurveyService surveyService;
    
    private SurveyResponseService surveyResponseService;
    
    private SubpopulationService subpopService;
    
    @Autowired
    public final void setScheduledActivityDao(ScheduledActivityDao activityDao) {
        this.activityDao = activityDao;
    }
    @Autowired
    public final void setActivityEventService(ActivityEventService activityEventService) {
        this.activityEventService = activityEventService;
    }
    @Autowired
    public final void setSchedulePlanService(SchedulePlanService schedulePlanService) {
        this.schedulePlanService = schedulePlanService;
    }
    @Autowired
    public final void setUserConsentDao(UserConsentDao userConsentDao) {
        this.userConsentDao = userConsentDao;
    }
    @Autowired
    public final void setSurveyService(SurveyService surveyService) {
        this.surveyService = surveyService;
    }
    @Autowired
    public final void setSurveyResponseService(SurveyResponseService surveyResponseService) {
        this.surveyResponseService = surveyResponseService;
    }
    @Autowired
    public final void setSubpopulationService(SubpopulationService subpopService) {
        this.subpopService = subpopService;
    }
    
    public List<ScheduledActivity> getScheduledActivities(User user, ScheduleContext context) {
        checkNotNull(user);
        checkNotNull(context);
        
        Validate.nonEntityThrowingException(VALIDATOR, context);
        
        // Add events for scheduling
        Map<String, DateTime> events = createEventsMap(context);
        ScheduleContext newContext = new ScheduleContext.Builder()
            .withContext(context).withEvents(events).build();
        
        // Get scheduled activities, persisted activities, and compare them
        List<ScheduledActivity> scheduledActivities = scheduleActivitiesForPlans(newContext);
        List<ScheduledActivity> dbActivities = activityDao.getActivities(newContext.getZone(), scheduledActivities);
        
        List<ScheduledActivity> saves = updateActivitiesAndCollectSaves(scheduledActivities, dbActivities);
        
        // if a survey activity, it may need a survey response generated (currently we're not using these though).
        for (ScheduledActivity schActivity : saves) {
            Activity activity = createSurveyResponseIfNeeded(
                    context.getStudyIdentifier(), context.getHealthCode(), schActivity.getActivity());
            schActivity.setActivity(activity);
        }
        activityDao.saveActivities(saves);

        return orderActivities(scheduledActivities);
    }
    
    public void updateScheduledActivities(String healthCode, List<ScheduledActivity> scheduledActivities) {
        checkArgument(isNotBlank(healthCode));
        checkNotNull(scheduledActivities);
        
        List<ScheduledActivity> activitiesToSave = Lists.newArrayListWithCapacity(scheduledActivities.size());
        for (int i=0; i < scheduledActivities.size(); i++) {
            ScheduledActivity schActivity = scheduledActivities.get(i);
            if (schActivity == null) {
                throw new BadRequestException("A task in the array is null");
            }
            if (schActivity.getGuid() == null) {
                throw new BadRequestException(String.format("Task #%s has no GUID", i));
            }
            if (schActivity.getStartedOn() != null || schActivity.getFinishedOn() != null) {
                // We do not need to add the time zone here. Not returning these to the user.
                ScheduledActivity dbActivity = activityDao.getActivity(null, healthCode, schActivity.getGuid());
                if (schActivity.getStartedOn() != null) {
                    dbActivity.setStartedOn(schActivity.getStartedOn());
                }
                if (schActivity.getFinishedOn() != null) {
                    dbActivity.setFinishedOn(schActivity.getFinishedOn());
                    activityEventService.publishActivityFinishedEvent(dbActivity);
                }
                activitiesToSave.add(dbActivity);
            }
        }
        activityDao.updateActivities(healthCode, activitiesToSave);
    }
    
    public void deleteActivitiesForUser(String healthCode) {
        checkArgument(isNotBlank(healthCode));
        
        activityDao.deleteActivitiesForUser(healthCode);
    }
    
    protected List<ScheduledActivity> updateActivitiesAndCollectSaves(List<ScheduledActivity> scheduledActivities, List<ScheduledActivity> dbActivities) {
        Map<String, ScheduledActivity> dbMap = Maps.uniqueIndex(dbActivities, ScheduledActivity::getGuid);
        
        List<ScheduledActivity> saves = Lists.newArrayList();
        for (int i=0; i < scheduledActivities.size(); i++) {
            ScheduledActivity activity = scheduledActivities.get(i);
            
            ScheduledActivity dbActivity = dbMap.get(activity.getGuid());
            if (dbActivity != null) {
                scheduledActivities.set(i, dbActivity);
            } else if (activity.getStatus() != ScheduledActivityStatus.EXPIRED) {
                saves.add(activity);    
            }
        }
        return saves;
    }
    
    protected List<ScheduledActivity> orderActivities(List<ScheduledActivity> activities) {
        return activities.stream()
            .filter(activity -> ScheduledActivityStatus.VISIBLE_STATUSES.contains(activity.getStatus()))
            .sorted(comparing(ScheduledActivity::getScheduledOn))
            .collect(toImmutableList());
    }
    
    /**
     * @param user
     * @return
     */
    private Map<String, DateTime> createEventsMap(ScheduleContext context) {
        Map<String,DateTime> events = activityEventService.getActivityEventMap(context.getHealthCode());
        if (!events.containsKey("enrollment")) {
            return createEnrollmentEventFromConsent(context, events);
        }
        return events;
    }
    
    /**
     * No events have been recorded for this participant, so get an enrollment event from the consent records.
     * We have back-filled this event, so this should no longer be needed, but it is left here just in case.
     * @param user
     * @param events
     * @return
     */
    private Map<String, DateTime> createEnrollmentEventFromConsent(ScheduleContext context, Map<String, DateTime> events) {
        // This should no longer happen, but in case a record was never migrated, go back to the consents to find the 
        // enrollment date. It's the earliest of all the signature dates.
        long signedOn = Long.MAX_VALUE;
        List<Subpopulation> subpops = subpopService.getSubpopulations(context.getStudyIdentifier());
        for (Subpopulation subpop : subpops) {
            UserConsent consent = userConsentDao.getActiveUserConsent(context.getHealthCode(), subpop.getGuid());
            if (consent != null && consent.getSignedOn() < signedOn) {
                signedOn = consent.getSignedOn();
            }
        }
        Map<String,DateTime> newEvents = Maps.newHashMap();
        newEvents.putAll(events);
        newEvents.put("enrollment", new DateTime(signedOn));
        LOG.warn("Enrollment missing from activity event table, pulling from consent records");
        return newEvents;
    }
   
    private List<ScheduledActivity> scheduleActivitiesForPlans(ScheduleContext context) {
        List<ScheduledActivity> scheduledActivities = Lists.newArrayList();
        
        List<SchedulePlan> plans = schedulePlanService.getSchedulePlans(context.getClientInfo(),
                context.getStudyIdentifier());
        for (SchedulePlan plan : plans) {
            Schedule schedule = plan.getStrategy().getScheduleForUser(plan, context);
            if (schedule != null) {
                List<ScheduledActivity> activities = schedule.getScheduler().getScheduledActivities(plan, context);
                scheduledActivities.addAll(activities);    
            } else {
                LOG.warn("Schedule plan "+plan.getLabel()+" has no schedule for user "+context.getUserId());
            }
        }
        return scheduledActivities;
    }
    
    private Activity createSurveyResponseIfNeeded(StudyIdentifier studyIdentifier, String healthCode, Activity activity) {
        // If this activity is a task activity, or the survey response for this survey has already been determined
        // and added to the activity, then do not generate a survey response for this activity.
        if (activity.getActivityType() == ActivityType.TASK || activity.getSurveyResponse() != null) {
            return activity;
        }
        
        // Get a survey reference and if necessary, resolve the timestamp to use for the survey
        SurveyReference ref = activity.getSurvey();
        GuidCreatedOnVersionHolder keys = new GuidCreatedOnVersionHolderImpl(ref);
        if (keys.getCreatedOn() == 0L) {
            keys = surveyService.getSurveyMostRecentlyPublishedVersion(studyIdentifier, ref.getGuid());
        }   
        
        // Now create a response for that specific survey version
        SurveyResponseView response = surveyResponseService.createSurveyResponse(keys, healthCode, EMPTY_ANSWERS, null);
        
        // And reconstruct the activity with that survey instance as well as the new response object.
        return new Activity.Builder()
            .withLabel(activity.getLabel())
            .withLabelDetail(activity.getLabelDetail())
            .withSurvey(response.getSurvey().getIdentifier(), keys.getGuid(), ref.getCreatedOn())
            .withSurveyResponse(response.getResponse().getIdentifier())
            .build();
    }
    
}
