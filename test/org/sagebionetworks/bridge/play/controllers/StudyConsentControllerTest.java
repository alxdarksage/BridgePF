package org.sagebionetworks.bridge.play.controllers;

import static org.sagebionetworks.bridge.Roles.DEVELOPER;
import static org.sagebionetworks.bridge.Roles.RESEARCHER;
import static org.sagebionetworks.bridge.TestUtils.assertResult;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.dynamodb.DynamoStudy;
import org.sagebionetworks.bridge.dynamodb.DynamoStudyConsent1;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.studies.StudyIdentifierImpl;
import org.sagebionetworks.bridge.models.subpopulations.StudyConsent;
import org.sagebionetworks.bridge.models.subpopulations.StudyConsentForm;
import org.sagebionetworks.bridge.models.subpopulations.StudyConsentView;
import org.sagebionetworks.bridge.models.subpopulations.Subpopulation;
import org.sagebionetworks.bridge.models.subpopulations.SubpopulationGuid;
import org.sagebionetworks.bridge.services.StudyConsentService;
import org.sagebionetworks.bridge.services.StudyService;
import org.sagebionetworks.bridge.services.SubpopulationService;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Lists;

import play.mvc.Result;
import play.test.Helpers;

// Not adding tests for deprecated methods. They all call more up-to-date methods.

@RunWith(MockitoJUnitRunner.class)
public class StudyConsentControllerTest {
    
    private static final String GUID = "guid";
    private static final String DATETIME_STRING = DateTime.now().toString();
    private static final SubpopulationGuid SUBPOP_GUID = SubpopulationGuid.create(GUID);
    private static final StudyIdentifier STUDY_ID = new StudyIdentifierImpl("test-study");
    private static final Study STUDY = new DynamoStudy();
    static {
        STUDY.setIdentifier("test-study");
    }

    @Mock
    private StudyService studyService;
    @Mock
    private StudyConsentService studyConsentService;
    @Mock
    private SubpopulationService subpopService;
    @Mock
    private Subpopulation subpopulation;
    
    private StudyConsentController controller;
    
    private UserSession session;

    @Before
    public void before() throws Exception {
        TestUtils.mockPlay().mock();
        
        controller = spy(new StudyConsentController());
        controller.setStudyConsentService(studyConsentService);
        controller.setSubpopulationService(subpopService);
        controller.setStudyService(studyService);
        
        session = new UserSession();
        session.setStudyIdentifier(STUDY_ID);
        session.setAuthenticated(true);
        
        when(subpopService.getSubpopulation(STUDY_ID, SubpopulationGuid.create(GUID))).thenReturn(subpopulation);
    }
    
    @Test
    public void getAllConsentsV2() throws Exception {
        doReturn(session).when(controller).getAuthenticatedSession(DEVELOPER);
        
        List<StudyConsent> consents = Lists.newArrayList(new DynamoStudyConsent1(), new DynamoStudyConsent1());
        when(studyConsentService.getAllConsents(SUBPOP_GUID)).thenReturn(consents);
        
        Result result = controller.getAllConsentsV2(GUID);
        TestUtils.assertResult(result, 200);
        
        // Do not need to extensively verify, just verify contents are returned in ResourceList
        JsonNode node = BridgeObjectMapper.get().readTree(Helpers.contentAsString(result));
        assertEquals(2, node.get("items").size());
    }

    @Test
    public void getActiveConsentV2() throws Exception {
        doReturn(session).when(controller).getAuthenticatedSession(DEVELOPER);
        
        StudyConsentView view = new StudyConsentView(new DynamoStudyConsent1(), "<document/>");
        when(studyConsentService.getActiveConsent(any())).thenReturn(view);
        
        Result result = controller.getActiveConsentV2(GUID);
        TestUtils.assertResult(result, 200);
        
        JsonNode node = BridgeObjectMapper.get().readTree(Helpers.contentAsString(result));
        assertEquals("<document/>", node.get("documentContent").asText());
    }
    
    @Test
    public void getMostRecentConsentV2() throws Exception {
        doReturn(session).when(controller).getAuthenticatedSession(DEVELOPER);
        
        StudyConsentView view = new StudyConsentView(new DynamoStudyConsent1(), "<document/>");
        when(studyConsentService.getMostRecentConsent(SUBPOP_GUID)).thenReturn(view);
        
        Result result = controller.getMostRecentConsentV2(GUID);
        TestUtils.assertResult(result, 200);
        
        JsonNode node = BridgeObjectMapper.get().readTree(Helpers.contentAsString(result));
        assertEquals("<document/>", node.get("documentContent").asText());
    }

    @Test
    public void getConsentV2() throws Exception {
        doReturn(session).when(controller).getAuthenticatedSession(DEVELOPER);
        
        StudyConsentView view = new StudyConsentView(new DynamoStudyConsent1(), "<document/>");
        when(studyConsentService.getConsent(SUBPOP_GUID, DateTime.parse(DATETIME_STRING).getMillis())).thenReturn(view);
        
        Result result = controller.getConsentV2(GUID, DATETIME_STRING);
        TestUtils.assertResult(result, 200);
        
        JsonNode node = BridgeObjectMapper.get().readTree(Helpers.contentAsString(result));
        assertEquals("<document/>", node.get("documentContent").asText());
    }
    
    @Test
    public void addConsentV2() throws Exception {
        doReturn(session).when(controller).getAuthenticatedSession(DEVELOPER);
        
        StudyConsentForm form = new StudyConsentForm("<document/>");
        TestUtils.mockPlay().withBody(form).mock();
        
        StudyConsentView view = new StudyConsentView(new DynamoStudyConsent1(), "<document/>");
        when(studyConsentService.addConsent(eq(SUBPOP_GUID), any())).thenReturn(view);
        
        Result result = controller.addConsentV2(GUID);
        TestUtils.assertResult(result, 201);
        
        JsonNode node = BridgeObjectMapper.get().readTree(Helpers.contentAsString(result));
        assertEquals("<document/>", node.get("documentContent").asText());
    }

    @Test
    public void publishConsentV2() throws Exception {
        doReturn(session).when(controller).getAuthenticatedSession(DEVELOPER);
        
        when(studyService.getStudy(STUDY_ID)).thenReturn(STUDY);
        
        Result result = controller.publishConsentV2(GUID, DATETIME_STRING);
        assertResult(result, 200, "Consent document set as active.");

        verify(studyConsentService).publishConsent(STUDY, subpopulation, DateTime.parse(DATETIME_STRING).getMillis());
    }
    
}
