package org.sagebionetworks.bridge.models.schedules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.sagebionetworks.bridge.TestConstants.TEST_STUDY_IDENTIFIER;

import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.dynamodb.DynamoSchedulePlan;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.ClientInfo;
import org.sagebionetworks.bridge.models.accounts.User;
import org.sagebionetworks.bridge.models.schedules.CriteriaScheduleStrategy.ScheduleCriteria;

import com.google.common.collect.Sets;

public class CriteriaScheduleStrategyTest {
    
    private Schedule strategyWithAppVersions = new Schedule();
    private Schedule strategyWithOneRequiredDataGroup = new Schedule();
    private Schedule strategyWithRequiredDataGroups = new Schedule();
    private Schedule strategyWithOneProhibitedDataGroup = new Schedule();
    private Schedule strategyWithProhibitedDataGroups = new Schedule();
    private Schedule strategyNoCriteria = new Schedule();
    
    private CriteriaScheduleStrategy strategy;
    private static final SchedulePlan PLAN = new DynamoSchedulePlan();
    
    @Before
    public void before() {
        strategy = new CriteriaScheduleStrategy();
        strategyWithAppVersions.setLabel("Strategy With App Versions");
        strategyWithOneRequiredDataGroup.setLabel("Strategy With One Required Data Group");
        strategyWithRequiredDataGroups.setLabel("Strategy With Required Data Groups");
        strategyWithOneProhibitedDataGroup.setLabel("Strategy With One Prohibited Data Group");
        strategyWithProhibitedDataGroups.setLabel("Strategy With One Prohibited Data Groups");
        strategyNoCriteria.setLabel("Strategy No Criteria");
    }
    
    @Test
    public void canSerialize() throws Exception {
        setUpStrategyWithAppVersions();
        setUpStrategyNoCriteria();
        
        String json = BridgeObjectMapper.get().writeValueAsString(strategy);
        CriteriaScheduleStrategy newStrategy = BridgeObjectMapper.get().readValue(json, CriteriaScheduleStrategy.class);
        
        assertEquals(strategy, newStrategy);
    }
    
    @Test
    public void filtersOnMinAppVersion() {
        setUpStrategyWithAppVersions();
        setUpStrategyNoCriteria();
        
        Schedule schedule = getSchedule(ClientInfo.UNKNOWN_CLIENT);
        assertEquals(strategyWithAppVersions, schedule);
        
        schedule = getSchedule(ClientInfo.fromUserAgentCache("app/2"));
        assertEquals(strategyNoCriteria, schedule);
    }
    
    @Test
    public void filtersOnMaxAppVersion() {
        setUpStrategyWithAppVersions();
        setUpStrategyNoCriteria();
        
        Schedule schedule = getSchedule(ClientInfo.UNKNOWN_CLIENT);
        assertEquals(strategyWithAppVersions, schedule);
        
        schedule = getSchedule(ClientInfo.fromUserAgentCache("app/44"));
        assertEquals(strategyNoCriteria, schedule);
    }
    
    @Test
    public void filtersOnRequiredDataGroup() {
        setUpStrategyWithOneRequiredDataGroup();
        setUpStrategyNoCriteria();
        
        Schedule schedule = getSchedule(Sets.newHashSet("group1"));
        assertEquals(strategyWithOneRequiredDataGroup, schedule);
        
        schedule = getSchedule(Sets.newHashSet("someRandomToken"));
        assertEquals(strategyNoCriteria, schedule);
    }
    
    @Test
    public void filtersOnRequiredDataGroups() {
        setUpStrategyWithRequiredDataGroups();
        setUpStrategyNoCriteria();
        
        Schedule schedule = getSchedule(Sets.newHashSet("group1"));
        assertEquals(strategyNoCriteria, schedule);
        
        schedule = getSchedule(Sets.newHashSet("group1","group2","group3"));
        assertEquals(strategyWithRequiredDataGroups, schedule);
        
        schedule = getSchedule(Sets.newHashSet("someRandomToken"));
        assertEquals(strategyNoCriteria, schedule);
    }
    
    @Test
    public void filtersOnProhibitedDataGroup() {
        setUpStrategyWithOneProhibitedDataGroup();
        setUpStrategyNoCriteria();
        
        Schedule schedule = getSchedule(Sets.newHashSet("groupNotProhibited"));
        assertEquals(strategyWithOneProhibitedDataGroup, schedule);
        
        schedule = getSchedule(Sets.newHashSet("group1"));
        assertEquals(strategyNoCriteria, schedule);
    }
    
    @Test
    public void filtersOnProhibitedDataGroups() {
        setUpStrategyWithProhibitedDataGroups();
        setUpStrategyNoCriteria();
        
        Schedule schedule = getSchedule(Sets.newHashSet("group1"));
        assertEquals(strategyNoCriteria, schedule);
        
        schedule = getSchedule(Sets.newHashSet("group1","foo"));
        assertEquals(strategyNoCriteria, schedule);
        
        schedule = getSchedule(Sets.newHashSet());
        assertEquals(strategyWithProhibitedDataGroups, schedule);
    }
    
    @Test
    public void noMatchingFilterReturnsNull() {
        setUpStrategyWithAppVersions();
        setUpStrategyWithProhibitedDataGroups();
        
        User user = getUser();
        user.setDataGroups(Sets.newHashSet("group1"));
        ScheduleContext context = new ScheduleContext.Builder()
                .withClientInfo(ClientInfo.fromUserAgentCache("app/44"))
                .withUser(user).build();

        Schedule schedule = strategy.getScheduleForUser(PLAN, context);
        assertNull(schedule);
    }
    
    @Test
    public void canMixMultipleCriteria() {
        setUpStrategyWithAppVersions();
        setUpStrategyWithOneRequiredDataGroup();
        setUpStrategyWithProhibitedDataGroups();
        
        User user = getUser();
        user.setDataGroups(Sets.newHashSet("group3"));
        ScheduleContext context = new ScheduleContext.Builder()
                .withClientInfo(ClientInfo.fromUserAgentCache("app/44"))
                .withUser(user).build();
        
        Schedule schedule = strategy.getScheduleForUser(PLAN, context);
        assertEquals(strategyWithProhibitedDataGroups, schedule);
    }

    @Test
    public void canGetAllPossibleScheduled() {
        setUpStrategyWithAppVersions();
        setUpStrategyWithOneRequiredDataGroup();
        setUpStrategyWithProhibitedDataGroups();

        List<Schedule> schedules = strategy.getAllPossibleSchedules();
        assertEquals(3, schedules.size());
    }
    
    @Test
    public void validate() {
        
    }
    
    private Schedule getSchedule(ClientInfo info) {
        ScheduleContext context = new ScheduleContext.Builder().withClientInfo(info).build();
        return strategy.getScheduleForUser(PLAN, context);
    }
    
    private Schedule getSchedule(Set<String> dataGroups) {
        User user = getUser();
        user.setDataGroups(dataGroups);
        ScheduleContext context = new ScheduleContext.Builder()
                .withClientInfo(ClientInfo.UNKNOWN_CLIENT).withUser(user).build();
        return strategy.getScheduleForUser(PLAN, context);
    }
    
    private User getUser() {
        User user = new User();
        user.setHealthCode("AAA");
        user.setStudyKey(TEST_STUDY_IDENTIFIER);
        return user;
    }
    
    private void setUpStrategyWithAppVersions() {
        strategy.addCriteria(new ScheduleCriteria.Builder()
            .withSchedule(strategyWithAppVersions)
            .withMinAppVersion(4)
            .withMaxAppVersion(12).build());
    }

    private void setUpStrategyNoCriteria() {
        strategy.addCriteria(new ScheduleCriteria.Builder()
            .withSchedule(strategyNoCriteria).build());
    }

    private void setUpStrategyWithOneRequiredDataGroup() {
        strategy.addCriteria(new ScheduleCriteria.Builder()
            .addRequiredGroup("group1")
            .withSchedule(strategyWithOneRequiredDataGroup).build());
    }
    
    private void setUpStrategyWithRequiredDataGroups() {
        strategy.addCriteria(new ScheduleCriteria.Builder()
            .addRequiredGroup("group1", "group2")
            .withSchedule(strategyWithRequiredDataGroups).build());
    }
    
    private void setUpStrategyWithOneProhibitedDataGroup() {
        strategy.addCriteria(new ScheduleCriteria.Builder()
            .addProhibitedGroup("group1")
            .withSchedule(strategyWithOneProhibitedDataGroup).build());
    }
    
    private void setUpStrategyWithProhibitedDataGroups() {
        strategy.addCriteria(new ScheduleCriteria.Builder()
            .addProhibitedGroup("group1","group2")
            .withSchedule(strategyWithProhibitedDataGroups).build());
    }
}
