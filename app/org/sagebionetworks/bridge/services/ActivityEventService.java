package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sagebionetworks.bridge.BridgeUtils.COMMA_JOINER;

import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;

import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Period;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.dao.ActivityEventDao;
import org.sagebionetworks.bridge.dynamodb.DynamoActivityEvent;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.models.activities.ActivityEvent;
import org.sagebionetworks.bridge.models.activities.ActivityEventObjectType;
import org.sagebionetworks.bridge.models.activities.ActivityEventType;
import org.sagebionetworks.bridge.models.schedules.ScheduledActivity;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.subpopulations.ConsentSignature;
import org.sagebionetworks.bridge.models.surveys.SurveyAnswer;

@Component
public class ActivityEventService {

    private ActivityEventDao activityEventDao;

    @Autowired
    final void setActivityEventDao(ActivityEventDao activityEventDao) {
        this.activityEventDao = activityEventDao;
    }

    /**
     * Publishes a custom event. Note that this automatically prepends "custom:" to the event key to form the event ID
     * (eg, event key "studyBurstStart" becomes event ID "custom:studyBurstStart"). Also note that the event key must
     * defined in the study (either in activityEventKeys or in AutomaticCustomEvents).
     */
    public void publishCustomEvent(Study study, String healthCode, String eventKey, DateTime timestamp) {
        checkNotNull(healthCode);
        checkNotNull(eventKey);

        if (!study.getActivityEventKeys().contains(eventKey)
                && !study.getAutomaticCustomEvents().containsKey(eventKey)) {
            throw new BadRequestException("Study's ActivityEventKeys does not contain eventKey: " + eventKey);
        }

        ActivityEvent event = new DynamoActivityEvent.Builder()
                .withHealthCode(healthCode)
                .withObjectType(ActivityEventObjectType.CUSTOM)
                .withObjectId(eventKey)
                .withTimestamp(timestamp).build();
        activityEventDao.publishEvent(event, true);
    }

    /**
     * Publishes the enrollment event for a user, as well as all of the automatic custom events that trigger on
     * enrollment time.
     */
    public void publishEnrollmentEvent(Study study, String healthCode, ConsentSignature signature) {
        checkNotNull(signature);

        // Create enrollment event. Use UTC for the timezone. DateTimes are used for period calculations, but since we
        // store everything as epoch milliseconds, the timezone should have very little affect.
        DateTime enrollment = new DateTime(signature.getSignedOn(), DateTimeZone.UTC);
        ActivityEvent event = new DynamoActivityEvent.Builder()
            .withHealthCode(healthCode)
            .withTimestamp(enrollment)
            .withObjectType(ActivityEventObjectType.ENROLLMENT).build();
        activityEventDao.publishEvent(event, true);

        // Create automatic events, as defined in the study
        for (Map.Entry<String, String> oneAutomaticEvent : study.getAutomaticCustomEvents().entrySet()) {
            String automaticEventKey = oneAutomaticEvent.getKey();
            Period automaticEventDelay = Period.parse(oneAutomaticEvent.getValue());
            DateTime automaticEventTime = enrollment.plus(automaticEventDelay);
            publishCustomEvent(study, healthCode, automaticEventKey, automaticEventTime);
        }
    }
    
    public void publishQuestionAnsweredEvent(String healthCode, SurveyAnswer answer) {
        checkNotNull(healthCode);
        checkNotNull(answer);
        
        ActivityEvent event = new DynamoActivityEvent.Builder()
            .withHealthCode(healthCode)
            .withTimestamp(answer.getAnsweredOn())
            .withObjectType(ActivityEventObjectType.QUESTION)
            .withObjectId(answer.getQuestionGuid())
            .withEventType(ActivityEventType.ANSWERED)
            .withAnswerValue(COMMA_JOINER.join(answer.getAnswers())).build();
        activityEventDao.publishEvent(event, true);
    }
    
    public void publishActivityFinishedEvent(ScheduledActivity schActivity) {
        checkNotNull(schActivity);
        
        // If there's no colon, this is an existing activity and it cannot fire an 
        // activity event. Quietly ignore this until we have migrated activities.
        if (schActivity.getGuid().contains(":")) {
            String activityGuid = schActivity.getGuid().split(":")[0];
            
            ActivityEvent event = new DynamoActivityEvent.Builder()
                .withHealthCode(schActivity.getHealthCode())
                .withObjectType(ActivityEventObjectType.ACTIVITY)
                .withObjectId(activityGuid)
                .withEventType(ActivityEventType.FINISHED)
                .withTimestamp(schActivity.getFinishedOn())
                .build();

            activityEventDao.publishEvent(event, true);
        }
    }
    
    /**
     * ActivityEvents can be published directly, although all supported events have a more 
     * specific service method that should be preferred. This method can be used for 
     * edge cases (like answering a question or finishing a survey through the bulk import 
     * system).
     */
    public void publishActivityEvent(ActivityEvent event, boolean enforceLater) {
        checkNotNull(event);
        
        activityEventDao.publishEvent(event, enforceLater);
    }

    /**
     * Gets the activity events times for a specific user in order to schedule against them.
     */
    public Map<String, DateTime> getActivityEventMap(String healthCode) {
        checkNotNull(healthCode);
        return activityEventDao.getActivityEventMap(healthCode);
    }

    public List<ActivityEvent> getActivityEventList(String healthCode) {
        Map<String, DateTime> activityEvents = getActivityEventMap(healthCode);

        List<ActivityEvent> activityEventList = Lists.newArrayList();
        for (Map.Entry<String, DateTime> entry : activityEvents.entrySet()) {
            DynamoActivityEvent event = new DynamoActivityEvent();
            event.setEventId(entry.getKey());

            DateTime timestamp = entry.getValue();
            if (timestamp !=null) {
                event.setTimestamp(timestamp.getMillis());
            }

            activityEventList.add(event);
        }
        return activityEventList;
    }

    public void deleteActivityEvents(String healthCode) {
        checkNotNull(healthCode);
        activityEventDao.deleteActivityEvents(healthCode);
    }
    
    public void deleteActivityEvent(String healthCode, String eventId) {
        checkNotNull(healthCode);

        if (StringUtils.isBlank(eventId)) {
            throw new BadRequestException("Invalid event ID");
        }
        activityEventDao.deleteActivityEvent(healthCode, eventId);
    }
    
}
