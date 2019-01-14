package org.sagebionetworks.bridge.play.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.bridge.TestUtils.assertResult;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.sagebionetworks.bridge.Roles;
import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.config.BridgeConfig;
import org.sagebionetworks.bridge.config.Environment;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.exceptions.NotAuthenticatedException;
import org.sagebionetworks.bridge.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.CriteriaContext;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifier;
import org.sagebionetworks.bridge.models.accounts.FPHSExternalIdentifier;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.services.AuthenticationService;
import org.sagebionetworks.bridge.services.ConsentService;
import org.sagebionetworks.bridge.services.FPHSService;
import org.sagebionetworks.bridge.services.NotificationTopicService;
import org.sagebionetworks.bridge.services.SessionUpdateService;
import org.sagebionetworks.bridge.services.StudyService;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import play.mvc.Result;
import play.test.Helpers;

public class FPHSControllerTest {

    private FPHSController controller;
    private AuthenticationService authenticationService;
    private FPHSService fphsService;
    private ConsentService consentService;

    @Before
    public void before() {
        // Mock dependent services
        fphsService = mock(FPHSService.class);

        authenticationService = mock(AuthenticationService.class);

        consentService = mock(ConsentService.class);

        SessionUpdateService sessionUpdateService = new SessionUpdateService();
        sessionUpdateService.setCacheProvider(mock(CacheProvider.class));
        sessionUpdateService.setConsentService(consentService);
        sessionUpdateService.setNotificationTopicService(mock(NotificationTopicService.class));

        Study study = Study.create();
        study.setIdentifier(TestConstants.TEST_STUDY_IDENTIFIER);

        StudyService mockStudyService = mock(StudyService.class);
        when(mockStudyService.getStudy(TestConstants.TEST_STUDY)).thenReturn(study);

        BridgeConfig mockBridgeConfig = mock(BridgeConfig.class);
        when(mockBridgeConfig.getEnvironment()).thenReturn(Environment.UAT);
        
        // Spy controller
        controller = spy(new FPHSController());
        controller.setFPHSService(fphsService);
        controller.setAuthenticationService(authenticationService);
        controller.setSessionUpdateService(sessionUpdateService);
        controller.setStudyService(mockStudyService);
        controller.setBridgeConfig(mockBridgeConfig);
    }
    
    private JsonNode resultToJson(Result result) throws Exception {
        String json = Helpers.contentAsString(result);
        return BridgeObjectMapper.get().readTree(json);
    }
    
    private void setData() throws Exception {
        FPHSExternalIdentifier id1 = FPHSExternalIdentifier.create("foo");
        id1.setRegistered(true);
        
        FPHSExternalIdentifier id2 = FPHSExternalIdentifier.create("bar");
        
        List<FPHSExternalIdentifier> identifiers = Lists.newArrayList(id1, id2);
        
        when(fphsService.getExternalIdentifiers()).thenReturn(identifiers);
    }
    
    private UserSession setUserSession() {
        StudyParticipant participant = new StudyParticipant.Builder().withHealthCode("BBB").build();
        
        UserSession session = new UserSession(participant);
        session.setStudyIdentifier(TestConstants.TEST_STUDY);
        session.setAuthenticated(true);
        
        doReturn(session).when(controller).getSessionIfItExists();
        return session;
    }
    
    @Test
    public void verifyOK() throws Exception {
        Result result = controller.verifyExternalIdentifier("foo");
        assertResult(result, 200);
        JsonNode node = resultToJson(result);
        
        // No session is required
        verifyNoMoreInteractions(authenticationService);
        assertEquals("foo", node.get("externalId").asText());
    }
    
    @Test
    public void verifyFails() throws Exception {
        doThrow(new EntityNotFoundException(FPHSExternalIdentifier.class)).when(fphsService).verifyExternalIdentifier(any());
        
        try {
            controller.verifyExternalIdentifier("foo");
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            assertEquals("ExternalIdentifier not found.", e.getMessage());
        }
    }
    
    @Test
    public void verifyFailsWhenNull() throws Exception {
        doThrow(new InvalidEntityException("ExternalIdentifier cannot be blank, null or missing.")).when(fphsService).verifyExternalIdentifier(any());
        
        try {
            controller.verifyExternalIdentifier(null);
            fail("Should have thrown exception");
        } catch(InvalidEntityException e) {
            assertEquals("ExternalIdentifier cannot be blank, null or missing.", e.getMessage());
        }
    }
    
    @Test
    public void registrationRequiresAuthenticatedConsentedUser() throws Exception {
        TestUtils.mockPlay().withBody(ExternalIdentifier.create(TestConstants.TEST_STUDY, "foo")).mock();
        try {
            controller.registerExternalIdentifier();
            fail("Should have thrown exception");
        } catch(NotAuthenticatedException e) {
            assertEquals("Not signed in.", e.getMessage());
        }
    }

    @Test
    public void registrationOK() throws Exception {
        UserSession session = setUserSession();
        TestUtils.mockPlay().withMockResponse()
            .withBody(ExternalIdentifier.create(TestConstants.TEST_STUDY, "foo")).mock();

        Result result = controller.registerExternalIdentifier();
        assertResult(result, 200, "External identifier added to user profile.");

        assertEquals(Sets.newHashSet("football_player"), session.getParticipant().getDataGroups());
        verify(consentService).getConsentStatuses(any(CriteriaContext.class));
    }
    
    @Test
    public void gettingAllIdentifiersRequiresAdmin() throws Exception {
        setData();
        
        // There's a user, but not an admin user
        UserSession session = setUserSession();
        try {
            controller.getExternalIdentifiers();
            fail("Should have thrown exception");
        } catch(UnauthorizedException e) {
            assertEquals("Caller does not have permission to access this service.", e.getMessage());
        }
        
        // Now when we have an admin user, we get back results
        session.setParticipant(new StudyParticipant.Builder()
                .copyOf(session.getParticipant())
                .withRoles(Sets.newHashSet(Roles.ADMIN)).build());
        
        Result result = controller.getExternalIdentifiers();
        assertResult(result, 200);
        
        JsonNode node = resultToJson(result);
        
        assertEquals(2, node.get("items").size());
        
        verify(fphsService).getExternalIdentifiers();
    }
    
    @Test
    public void addIdentifiersRequiresAdmin() throws Exception {
        FPHSExternalIdentifier id1 = FPHSExternalIdentifier.create("AAA");
        FPHSExternalIdentifier id2 = FPHSExternalIdentifier.create("BBB");
        TestUtils.mockPlay().withBody(Lists.newArrayList(id1, id2)).mock();
        
        // There's a user, but not an admin user
        setUserSession();
        try {
            controller.addExternalIdentifiers();
            fail("Should have thrown exception");
        } catch(UnauthorizedException e) {
            assertEquals("Caller does not have permission to access this service.", e.getMessage());
        }
    }
    
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    public void addIdentifiersOK() throws Exception {
        FPHSExternalIdentifier id1 = FPHSExternalIdentifier.create("AAA");
        FPHSExternalIdentifier id2 = FPHSExternalIdentifier.create("BBB");
        TestUtils.mockPlay().withBody(Lists.newArrayList(id1, id2)).mock();
        
        UserSession session = setUserSession();
        // Now when we have an admin user, we get back results
        session.setParticipant(new StudyParticipant.Builder().copyOf(session.getParticipant())
                .withRoles(Sets.newHashSet(Roles.ADMIN)).build());
        Result result = controller.addExternalIdentifiers();
        assertResult(result, 201, "External identifiers added.");
        
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(fphsService).addExternalIdentifiers(captor.capture());
        
        List<FPHSExternalIdentifier> passedList = (List<FPHSExternalIdentifier>)captor.getValue();
        assertEquals(2, passedList.size());
    }
}
