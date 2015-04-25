package org.sagebionetworks.bridge.dynamodb;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.joda.time.DateTime;
import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.dao.TaskDao;
import org.sagebionetworks.bridge.dao.UserConsentDao;
import org.sagebionetworks.bridge.models.User;
import org.sagebionetworks.bridge.models.UserConsent;
import org.sagebionetworks.bridge.models.schedules.Schedule;
import org.sagebionetworks.bridge.models.schedules.SchedulePlan;
import org.sagebionetworks.bridge.models.schedules.SchedulerFactory;
import org.sagebionetworks.bridge.models.schedules.Task;
import org.sagebionetworks.bridge.models.schedules.TaskScheduler;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.studies.StudyIdentifierImpl;
import org.sagebionetworks.bridge.services.SchedulePlanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper.FailedBatch;
import com.amazonaws.services.dynamodbv2.datamodeling.PaginatedQueryList;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

@Component
public class DynamoTaskDao implements TaskDao {
    
    private static final Comparator<Task> TASK_COMPARATOR = new Comparator<Task>() {
        @Override 
        public int compare(Task task1, Task task2) {
            int result = (int)(task1.getScheduledOn() - task2.getScheduledOn());
            if (result == 0) {
                result = task1.getActivity().getLabel().compareTo(task2.getActivity().getLabel());
            }
            return result;
        }
    };
    
    private DynamoDBMapper mapper;
    
    private SchedulePlanService schedulePlanService;
    
    private UserConsentDao userConsentDao;

    @Resource(name = "taskDdbMapper")
    public void setDdbMapper(DynamoDBMapper mapper) {
        this.mapper = mapper;
    }
    
    @Autowired
    public void setSchedulePlanService(SchedulePlanService schedulePlanService) {
        this.schedulePlanService = schedulePlanService;
    }
    
    @Autowired
    public void setUserConsentDao(UserConsentDao userConsentDao) {
        this.userConsentDao = userConsentDao;
    }

    /** {@inheritDoc} */
    @Override
    public List<Task> getTasks(User user, DateTime endsOn) {
        List<Task> scheduledTasks = scheduleTasksForPlans(user, endsOn);
        
        List<Task> tasksToSave = Lists.newArrayList();
        for (Task task : scheduledTasks) {
            if (taskRunDoesntExist(user.getHealthCode(), task)) {
                tasksToSave.add(task);
            }
        }
        if (!tasksToSave.isEmpty()) {
            List<FailedBatch> failures = mapper.batchSave(tasksToSave);
            BridgeUtils.ifFailuresThrowException(failures);
        }
        return getTasksWithoutScheduling(user);
    }
    
    /** {@inheritDoc} */
    @Override
    public List<Task> getTasksWithoutScheduling(User user) {
        List<Task> tasks = queryForTasks(user.getHealthCode());
        Collections.sort(tasks, TASK_COMPARATOR);
        return tasks;
    }
    
    /** {@inheritDoc} */
    @Override
    public void updateTasks(String healthCode, List<Task> tasks) {
        List<Task> tasksToSave = Lists.newArrayList();
        for (Task task : tasks) {
            if (task != null && (task.getStartedOn() != null || task.getFinishedOn() != null)) {
                DynamoTask hashKey = new DynamoTask();
                hashKey.setHealthCode(healthCode);
                hashKey.setGuid(task.getGuid());

                Task dbTask = mapper.load(hashKey);
                if (dbTask != null) {
                    if (task.getStartedOn() != null) {
                        dbTask.setStartedOn(task.getStartedOn());
                        dbTask.setHidesOn(new Long(Long.MAX_VALUE));
                    }
                    if (task.getFinishedOn() != null) {
                        dbTask.setFinishedOn(task.getFinishedOn());
                        dbTask.setHidesOn(task.getFinishedOn());
                    }
                    tasksToSave.add(dbTask);
                }
            }
        }
        if (!tasksToSave.isEmpty()) {
            List<FailedBatch> failures = mapper.batchSave(tasksToSave);
            BridgeUtils.ifFailuresThrowException(failures);
        }
    }
    
    /** {@inheritDoc} */
    @Override
    public void deleteTasks(String healthCode) {
        DynamoTask hashKey = new DynamoTask();
        hashKey.setHealthCode(healthCode);

        DynamoDBQueryExpression<DynamoTask> query = new DynamoDBQueryExpression<DynamoTask>().withHashKeyValues(hashKey);
        
        PaginatedQueryList<DynamoTask> queryResults = mapper.query(DynamoTask.class, query);
        
        // Confirmed that you have to transfer these tasks to a list or the batchDelete does not work. 
        List<DynamoTask> tasksToDelete = Lists.newArrayListWithCapacity(queryResults.size());
        tasksToDelete.addAll(queryResults);
        
        List<FailedBatch> failures = mapper.batchDelete(tasksToDelete);
        BridgeUtils.ifFailuresThrowException(failures);
    }

    private List<Task> queryForTasks(String healthCode) {
        DynamoTask hashKey = new DynamoTask();
        hashKey.setHealthCode(healthCode);

        // Exclude everything hidden before *now*
        AttributeValue attribute = new AttributeValue().withN(Long.toString(DateTime.now().getMillis()));
        Condition condition = new Condition()
            .withComparisonOperator(ComparisonOperator.GT)
            .withAttributeValueList(attribute);

        DynamoDBQueryExpression<DynamoTask> query = new DynamoDBQueryExpression<DynamoTask>()
            .withQueryFilterEntry("hidesOn", condition)
            .withHashKeyValues(hashKey);

        PaginatedQueryList<DynamoTask> queryResults = mapper.query(DynamoTask.class, query);
        List<Task> tasks = Lists.newArrayList();
        tasks.addAll(queryResults);
        /*
        for (DynamoTask task : queryResults) {
            tasks.add(task);
        }
        */
        return tasks;
    }
    
    /**
     * TODO: Once you've found the run key once, you don't need to query again. All the 
     * tasks will have been created.
     * @param healthCode
     * @param task
     * @return
     */
    private boolean taskRunDoesntExist(String healthCode, Task task) {
        DynamoTask hashKey = new DynamoTask();
        hashKey.setHealthCode(healthCode);
        hashKey.setRunKey(BridgeUtils.generateTaskKey(task));
        
        DynamoDBQueryExpression<DynamoTask> query = new DynamoDBQueryExpression<DynamoTask>()
            .withHashKeyValues(hashKey);

        return (mapper.count(DynamoTask.class, query) == 0);
    }

    /**
     * TODO: Right now this has to come from the consent record... we want a separate even table for this.
     * @param studyIdentifier
     * @param user
     * @return
     */
    private Map<String, DateTime> createEventsMap(User user) {
        Map<String,DateTime> events = Maps.newHashMap();
        UserConsent consent = userConsentDao.getUserConsent(user.getHealthCode(), new StudyIdentifierImpl(user.getStudyKey()));
        events.put("enrollment", new DateTime(consent.getSignedOn()));
        return events;
    }
    
    /**
     * Find all tasks from all schedules generated by all schedule plans, returning a list of tasks that are 
     * within the given time window. These tasks may or may not be persisted.
     * 
     * @param user
     * @param endsOn
     * @return
     */
    private List<Task> scheduleTasksForPlans(User user, DateTime endsOn) {
        List<Task> tasks = Lists.newArrayList();
        Map<String, DateTime> events = createEventsMap(user);
        StudyIdentifier studyId = new StudyIdentifierImpl(user.getStudyKey());

        List<SchedulePlan> plans = schedulePlanService.getSchedulePlans(studyId);
        for (SchedulePlan plan : plans) {
            Schedule schedule = plan.getStrategy().getScheduleForUser(studyId, plan, user);
            TaskScheduler scheduler = SchedulerFactory.getScheduler(plan.getGuid(), schedule);

            for (Task task : scheduler.getTasks(events, endsOn)) {
                task.setHealthCode(user.getHealthCode());
                tasks.add(task);
            }
        }
        return tasks;
    }
    
    
    /**
     * Generate a key that is based on the natural key of the task (schedule plan GUID, scheduled start time of the
     * task, and the activity reference). The same key will be generated each time for the same task, and the key 
     * should never collide with another key within the scope of an individual user's tasks.
     * 
     * @param task
     * @return
     */
    public String generateTaskKey(Task task) {
        checkNotNull(task.getSchedulePlanGuid());
        checkNotNull(task.getScheduledOn());
        checkNotNull(task.getActivity());
        checkNotNull(task.getActivity().getRef());
        
        return String.format("%s%s%s", task.getSchedulePlanGuid(), task.getScheduledOn(), task.getActivity().getRef());
    }

}
