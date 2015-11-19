package org.sagebionetworks.bridge.models.schedules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.sagebionetworks.bridge.TestConstants.TEST_STUDY_IDENTIFIER;

import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.dynamodb.DynamoSchedulePlan;
import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.ClientInfo;
import org.sagebionetworks.bridge.models.accounts.User;
import org.sagebionetworks.bridge.models.schedules.CriteriaScheduleStrategy.ScheduleCriteria;
import org.sagebionetworks.bridge.validators.SchedulePlanValidator;
import org.sagebionetworks.bridge.validators.Validate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.Sets;

public class CriteriaScheduleStrategyTest {
    
    private Schedule strategyWithAppVersions = makeValidSchedule("Strategy With App Versions");
    private Schedule strategyWithOneRequiredDataGroup = makeValidSchedule("Strategy With One Required Data Group");
    private Schedule strategyWithRequiredDataGroups = makeValidSchedule("Strategy With Required Data Groups");
    private Schedule strategyWithOneProhibitedDataGroup = makeValidSchedule("Strategy With One Prohibited Data Group");
    private Schedule strategyWithProhibitedDataGroups = makeValidSchedule("Strategy With One Prohibited Data Groups");
    private Schedule strategyNoCriteria = makeValidSchedule("Strategy No Criteria");
    
    private CriteriaScheduleStrategy strategy;
    private SchedulePlan plan;
    private SchedulePlanValidator validator;
    
    @Before
    public void before() {
        strategy = new CriteriaScheduleStrategy();

        plan = new DynamoSchedulePlan();
        plan.setLabel("Schedule plan label");
        plan.setStudyKey(TEST_STUDY_IDENTIFIER);
        plan.setStrategy(strategy);
        
        validator = new SchedulePlanValidator(Sets.newHashSet(), Sets.newHashSet("tapTest"));
    }
    
    @Test
    public void canSerialize() throws Exception {
        BridgeObjectMapper mapper = BridgeObjectMapper.get();
        
        setUpStrategyWithAppVersions();
        setUpStrategyNoCriteria();
        
        String json = mapper.writeValueAsString(strategy);
        
        JsonNode node = mapper.readTree(json);
        assertEquals("CriteriaScheduleStrategy", node.get("type").asText());
        assertNotNull(node.get("scheduleCriteria"));
        
        ArrayNode array = (ArrayNode)node.get("scheduleCriteria");
        JsonNode child1 = array.get(0);
        assertEquals("ScheduleCriteria", child1.get("type").asText());
        assertEquals(4, child1.get("minAppVersion").asInt());
        assertEquals(12, child1.get("maxAppVersion").asInt());
        assertNotNull(child1.get("allOfGroups"));
        assertNotNull(child1.get("noneOfGroups"));
        assertNotNull(child1.get("schedule"));
        
        // But mostly, if this isn't all serialized, and then deserialized, these won't be equal
        CriteriaScheduleStrategy newStrategy = mapper.readValue(json, CriteriaScheduleStrategy.class);
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

        Schedule schedule = strategy.getScheduleForUser(plan, context);
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
        
        Schedule schedule = strategy.getScheduleForUser(plan, context);
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
    public void validatePasses() {
        setUpStrategyWithAppVersions();
        setUpStrategyWithOneRequiredDataGroup();
        setUpStrategyWithProhibitedDataGroups();
        setUpStrategyNoCriteria();
        
        // We're looking here specifically for errors generated by the strategy, not the plan
        // it's embedded in or the schedules. I've made those valid so they don't add errors.
        strategy.addCriteria(new ScheduleCriteria.Builder()
                .withMinAppVersion(-2)
                .withMaxAppVersion(-10)
                .withSchedule(strategyWithOneRequiredDataGroup).build());
        
        try {
            Validate.entityThrowingException(validator, plan);            
        } catch(InvalidEntityException e) {
            String fieldName = "strategy.scheduleCriteria[1].allOfGroups";
            List<String> errors = e.getErrors().get(fieldName);
            assertEquals(fieldName+" 'group1' is not in enumeration: <no data groups declared>", errors.get(0));
            
            fieldName = "strategy.scheduleCriteria[2].noneOfGroups";
            errors = e.getErrors().get(fieldName);
            assertEquals(fieldName+" 'group2' is not in enumeration: <no data groups declared>", errors.get(0));
            assertEquals(fieldName+" 'group1' is not in enumeration: <no data groups declared>", errors.get(1));
            
            fieldName = "strategy.scheduleCriteria[4].minAppVersion";
            errors = e.getErrors().get(fieldName);
            assertEquals(fieldName+" cannot be negative", errors.get(0));
            
            fieldName = "strategy.scheduleCriteria[4].maxAppVersion";
            errors = e.getErrors().get(fieldName);
            assertEquals(fieldName+" cannot be less than minAppVersion", errors.get(0));
            assertEquals(fieldName+" cannot be negative", errors.get(1));
        }
    }
    
    private Schedule makeValidSchedule(String label) {
        Schedule schedule = new Schedule();
        schedule.setLabel(label);
        schedule.setScheduleType(ScheduleType.ONCE);
        schedule.addActivity(TestConstants.TEST_3_ACTIVITY);
        return schedule;
    }

    private Schedule getSchedule(ClientInfo info) {
        ScheduleContext context = new ScheduleContext.Builder().withClientInfo(info).build();
        return strategy.getScheduleForUser(plan, context);
    }
    
    private Schedule getSchedule(Set<String> dataGroups) {
        User user = getUser();
        user.setDataGroups(dataGroups);
        ScheduleContext context = new ScheduleContext.Builder()
                .withClientInfo(ClientInfo.UNKNOWN_CLIENT).withUser(user).build();
        return strategy.getScheduleForUser(plan, context);
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
