package org.sagebionetworks.bridge.models.schedules;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.schedules.CriteriaScheduleStrategy.ScheduleCriteria;

public class CriteriaScheduleStrategyTest {
    
    private CriteriaScheduleStrategy strategy;
    
    @Before
    public void before() {
        strategy = new CriteriaScheduleStrategy();
    }
    
    @Test
    public void canSerialize() throws Exception {
        ScheduleCriteria criteria = getCriteriaOne();
        strategy.addCriteria(criteria);

        criteria = getCriteriaTwo();
        strategy.addCriteria(criteria);
        
        String json = BridgeObjectMapper.get().writeValueAsString(strategy);
        
        CriteriaScheduleStrategy newStrategy = BridgeObjectMapper.get().readValue(json, CriteriaScheduleStrategy.class);
        assertEquals(strategy, newStrategy);
    }

    private ScheduleCriteria getCriteriaOne() {
        Schedule schedule = new Schedule();
        schedule.setScheduleType(ScheduleType.ONCE);
        schedule.addActivity(TestConstants.TEST_3_ACTIVITY);
        ScheduleCriteria criteria = new ScheduleCriteria.Builder()
            .withSchedule(schedule)
            .withMinAppVersion(2)
            .withMaxAppVersion(12)
            .addProhibitedGroup("notThisGroup")
            .addRequiredGroup("groupA","groupB").build();
        return criteria;
    }

    private ScheduleCriteria getCriteriaTwo() {
        ScheduleCriteria criteria;
        Schedule schedule = new Schedule();
        schedule.setScheduleType(ScheduleType.RECURRING);
        schedule.addActivity(TestConstants.TEST_3_ACTIVITY);
        schedule.setInterval("P3D");
        
        criteria = new ScheduleCriteria.Builder()
            .withSchedule(schedule)
            .withMaxAppVersion(4).build();
        return criteria;
    }

}
