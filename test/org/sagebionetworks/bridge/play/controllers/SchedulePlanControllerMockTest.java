package org.sagebionetworks.bridge.play.controllers;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import static org.mockito.Mockito.spy;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import org.sagebionetworks.bridge.Roles;
import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.dynamodb.DynamoSchedulePlan;
import org.sagebionetworks.bridge.dynamodb.DynamoStudy;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.ClientInfo;
import org.sagebionetworks.bridge.models.ResourceList;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.schedules.ABTestScheduleStrategy;
import org.sagebionetworks.bridge.models.schedules.Activity;
import org.sagebionetworks.bridge.models.schedules.Schedule;
import org.sagebionetworks.bridge.models.schedules.SchedulePlan;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.services.SchedulePlanService;
import org.sagebionetworks.bridge.services.StudyService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.Lists;

import play.mvc.Result;
import play.test.Helpers;

@RunWith(MockitoJUnitRunner.class)
public class SchedulePlanControllerMockTest {

    private SchedulePlanController controller;
    
    @Mock
    private StudyService mockStudyService;
    
    @Mock
    private SchedulePlanService mockSchedulePlanService;
    
    @Mock
    private UserSession mockUserSession;
    
    @Captor
    private ArgumentCaptor<SchedulePlan> schedulePlanCaptor;
    
    private Study study;
    
    @Before
    public void before() {
        controller = spy(new SchedulePlanController());
        
        study = new DynamoStudy();
        study.setIdentifier(TestConstants.TEST_STUDY_IDENTIFIER);
        
        when(mockStudyService.getStudy(study.getStudyIdentifier())).thenReturn(study);
        when(mockStudyService.getStudy(study.getIdentifier())).thenReturn(study);
        controller.setStudyService(mockStudyService);
        controller.setSchedulePlanService(mockSchedulePlanService);
        
        when(mockUserSession.getStudyIdentifier()).thenReturn(TestConstants.TEST_STUDY);
        doReturn(mockUserSession).when(controller).getAuthenticatedSession(Roles.DEVELOPER);
        doReturn(mockUserSession).when(controller).getAuthenticatedSession(Roles.DEVELOPER, Roles.ADMIN);
    }
    
    @Test
    public void getSchedulePlansForWorker() throws Exception {
        TestUtils.mockPlay().mock();
        doReturn(mockUserSession).when(controller).getAuthenticatedSession(Roles.WORKER);
        SchedulePlan plan = createSchedulePlan();
        when(mockSchedulePlanService.getSchedulePlans(ClientInfo.UNKNOWN_CLIENT, TestConstants.TEST_STUDY, false))
                .thenReturn(Lists.newArrayList(plan));
        
        Result result = controller.getSchedulePlansForWorker(TestConstants.TEST_STUDY_IDENTIFIER, "false");
        
        assertEquals(200, result.status());
        ResourceList<SchedulePlan> plans = TestUtils.getResponsePayload(result, new TypeReference<ResourceList<SchedulePlan>>() {});
        assertEquals(plan, plans.getItems().get(0));
    }

    @Test
    public void testCreateSchedulePlan() throws Exception {
        SchedulePlan plan = createSchedulePlan();

        TestUtils.mockPlay().withBody(plan).mock();
        
        when(mockSchedulePlanService.createSchedulePlan(eq(study), any())).thenReturn(plan);
        
        controller.createSchedulePlan();
        
        verify(mockSchedulePlanService).createSchedulePlan(eq(study), schedulePlanCaptor.capture());
        
        SchedulePlan savedPlan = schedulePlanCaptor.getValue();
        assertEquals(plan, savedPlan);
    }

    @Test
    public void updateSchedulePlan() throws Exception {
        SchedulePlan plan = createSchedulePlan();

        TestUtils.mockPlay().withBody(plan).mock();
        
        when(mockSchedulePlanService.updateSchedulePlan(eq(study), any())).thenReturn(plan);
        
        controller.updateSchedulePlan(plan.getGuid());
        
        verify(mockSchedulePlanService).updateSchedulePlan(eq(study), schedulePlanCaptor.capture());
        
        SchedulePlan savedPlan = schedulePlanCaptor.getValue();
        assertEquals(plan, savedPlan);
    }
    
    @Test
    public void getSchedulePlans() throws Exception {
        List<SchedulePlan> plans = Lists.newArrayList(TestUtils.getSimpleSchedulePlan(TestConstants.TEST_STUDY));
        
        when(mockSchedulePlanService.getSchedulePlans(any(), eq(study.getStudyIdentifier()), eq(false))).thenReturn(plans);
        
        Result result = controller.getSchedulePlans("false");
        TestUtils.assertResult(result, 200);
        
        verify(mockSchedulePlanService).getSchedulePlans(any(), eq(study.getStudyIdentifier()), eq(false));
        
        ResourceList<SchedulePlan> retrieved = BridgeObjectMapper.get().readValue(Helpers.contentAsString(result), new TypeReference<ResourceList<SchedulePlan>>() {});
        assertEquals(1, retrieved.getItems().size());
        assertEquals(plans.get(0).getGuid(), retrieved.getItems().get(0).getGuid());
    }
    
    @Test
    public void getSchedulePlan() throws Exception {
        SchedulePlan plan = TestUtils.getSimpleSchedulePlan(TestConstants.TEST_STUDY);
        plan.setGuid("GGG");
        
        when(mockSchedulePlanService.getSchedulePlan(study.getStudyIdentifier(), "GGG")).thenReturn(plan);
        
        Result result = controller.getSchedulePlan("GGG");
        TestUtils.assertResult(result, 200);
        
        verify(mockSchedulePlanService).getSchedulePlan(study.getStudyIdentifier(), "GGG");
        
        SchedulePlan retrieved = BridgeObjectMapper.get().readValue(Helpers.contentAsString(result), SchedulePlan.class);
        assertEquals(plan.getGuid(), retrieved.getGuid());
    }
    
    @Test
    public void deleteSchedulePlan() throws Exception {
        Result result = controller.deleteSchedulePlan("GGG", "false");
        TestUtils.assertResult(result, 200, "Schedule plan deleted.");
        
        verify(mockSchedulePlanService).deleteSchedulePlan(study.getStudyIdentifier(), "GGG");
    }
    
    @Test
    public void deleteSchedulePlanDefault() throws Exception {
        Result result = controller.deleteSchedulePlan("GGG", null);
        TestUtils.assertResult(result, 200, "Schedule plan deleted.");
        
        verify(mockSchedulePlanService).deleteSchedulePlan(study.getStudyIdentifier(), "GGG");
    }
    
    @Test
    public void deleteSchedulePlanPermanently() throws Exception {
        when(mockUserSession.isInRole(Roles.ADMIN)).thenReturn(true);
        
        Result result = controller.deleteSchedulePlan("GGG", "true");
        TestUtils.assertResult(result, 200, "Schedule plan deleted.");
        
        verify(mockSchedulePlanService).deleteSchedulePlanPermanently(study.getStudyIdentifier(), "GGG");
    }
    
    @Test
    public void deleteSchedulePlanPermanentlyOnlyLogicalForDeveloper() throws Exception {
        Result result = controller.deleteSchedulePlan("GGG", "true");
        TestUtils.assertResult(result, 200, "Schedule plan deleted.");
        
        verify(mockSchedulePlanService).deleteSchedulePlan(study.getStudyIdentifier(), "GGG");
    }
    
    private SchedulePlan createSchedulePlan() {
        ABTestScheduleStrategy strategy = new ABTestScheduleStrategy();
        Schedule schedule = new Schedule();
        strategy.addGroup(50, schedule);

        schedule = new Schedule();
        schedule.addActivity(new Activity.Builder().withLabel("Foo").build());
        strategy.addGroup(20, schedule);
        
        SchedulePlan plan = new DynamoSchedulePlan();
        plan.setGuid("schedulePlanGuid");
        plan.setStrategy(strategy);
        return plan;
    }
}
