package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Map;

import org.joda.time.DateTime;
import org.sagebionetworks.bridge.dao.ActivityEventDao;
import org.sagebionetworks.bridge.dynamodb.DynamoActivityEvent;
import org.sagebionetworks.bridge.models.activities.ActivityEvent;
import org.sagebionetworks.bridge.models.activities.ActivityEventObjectType;
import org.sagebionetworks.bridge.models.activities.ActivityEventType;
import org.sagebionetworks.bridge.models.schedules.ScheduledActivity;
import org.sagebionetworks.bridge.models.subpopulations.ConsentSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ActivityEventService {

    private ActivityEventDao activityEventDao;
    
    @Autowired
    public void setActivityEventDao(ActivityEventDao activityEventDao) {
        this.activityEventDao = activityEventDao;
    }
    
    public void publishEnrollmentEvent(String healthCode, ConsentSignature signature) {
        checkNotNull(signature);
        
        ActivityEvent event = new DynamoActivityEvent.Builder()
            .withHealthCode(healthCode)
            .withTimestamp(signature.getSignedOn())
            .withObjectType(ActivityEventObjectType.ENROLLMENT).build();
        activityEventDao.publishEvent(event);    
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

            activityEventDao.publishEvent(event);
        }
    }
    
    /**
     * ActivityEvents can be published directly, although all supported events have a more 
     * specific service method that should be preferred. This method can be used for 
     * edge cases (like answering a question or finishing a survey through the bulk import 
     * system).
     * 
     * @param event
     */
    public void publishActivityEvent(ActivityEvent event) {
        checkNotNull(event);
        activityEventDao.publishEvent(event);
    }

    /**
     * Gets the activity events times for a specific user in order to schedule against them.
     * @param healthCode
     * @return
     */
    public Map<String, DateTime> getActivityEventMap(String healthCode) {
        checkNotNull(healthCode);
        return activityEventDao.getActivityEventMap(healthCode);
    }

    public void deleteActivityEvents(String healthCode) {
        checkNotNull(healthCode);
        activityEventDao.deleteActivityEvents(healthCode);
    }

}
