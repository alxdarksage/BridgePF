package org.sagebionetworks.bridge.dao;

import java.util.List;

import org.sagebionetworks.bridge.models.schedules.ScheduleContext;
import org.sagebionetworks.bridge.models.schedules.ScheduledActivity;

public interface ScheduledActivityDao {
    
    /**
     * Load an individual activity.
     * @param healthCode
     * @param guid
     * @return
     */
    public ScheduledActivity getActivity(String healthCode, String guid);
    
    /**
     * Get a user's scheduled activities up to a target timestamp. This returns all activities that are not expired,
     * deleted or finished, as well as future activities that are scheduled but should not be started yet. Activities
     * are not necessarily saved in the database, however.
     * 
     * @param context
     * @return
     */
    public List<ScheduledActivity> getActivities(ScheduleContext context);
    
    /**
     * Save activities (activities will only be saved if they are not in the database).
     * @param activities
     */
    public void saveActivities(List<ScheduledActivity> activities);
    
    /**
     * Update the startedOn or finishedOn timestamps of the activities in the collection. Activities in this collection
     * should also have a GUID. All other fields are ignored. Health code is supplied here because these activities come from
     * the client and the client does not provide it.
     * 
     * @param healthCode
     * @param activities
     */
    public void updateActivities(String healthCode, List<ScheduledActivity> activities);
    
    /**
     * Physically delete all the activity records for this user. This method should only be called as a 
     * user is being deleted. To do a logical delete, add a "finishedOn" timestamp to a scheduled activity 
     * and update it. 
     * 
     * @param healthCode
     */
    public void deleteActivitiesForUser(String healthCode);
    
    /**
     * Delete the scheduled activities for a schedule plan (with the exception of started activities, which the user has
     * seen and started working on, it would be annoying for these to disappear on the user). We do this when a schedule
     * plan is updated or deleted, to update the tasks that are returned to a user so the reflect the current state of
     * the schedule plans.
     * 
     * @param schedulePlanGuid
     */
    public void deleteActivitiesForSchedulePlan(String schedulePlanGuid);
    
    /**
     * In calculating activities to return to a user, any activities found that are no longer in the list of scheduled
     * activites are deleted through this method
     * 
     * @param activitiesToDelete
     */
    public void deleteActivities(List<ScheduledActivity> activitiesToDelete);
}
