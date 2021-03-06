package org.sagebionetworks.bridge.play.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.bridge.TestConstants.TEST_CONTEXT;
import static org.sagebionetworks.bridge.TestUtils.assertResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Sets;

import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import org.sagebionetworks.bridge.BridgeConstants;
import org.sagebionetworks.bridge.Roles;
import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.config.BridgeConfig;
import org.sagebionetworks.bridge.config.Environment;
import org.sagebionetworks.bridge.dynamodb.DynamoStudy;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.exceptions.NotAuthenticatedException;
import org.sagebionetworks.bridge.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.exceptions.UnsupportedVersionException;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.CriteriaContext;
import org.sagebionetworks.bridge.models.Metrics;
import org.sagebionetworks.bridge.models.OperatingSystem;
import org.sagebionetworks.bridge.models.RequestInfo;
import org.sagebionetworks.bridge.models.accounts.AccountId;
import org.sagebionetworks.bridge.models.accounts.ConsentStatus;
import org.sagebionetworks.bridge.models.accounts.Verification;
import org.sagebionetworks.bridge.models.accounts.PasswordReset;
import org.sagebionetworks.bridge.models.accounts.SignIn;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.studies.StudyIdentifierImpl;
import org.sagebionetworks.bridge.services.AccountWorkflowService;
import org.sagebionetworks.bridge.services.AuthenticationService;
import org.sagebionetworks.bridge.services.AuthenticationService.ChannelType;
import org.sagebionetworks.bridge.services.StudyService;

import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;

@RunWith(MockitoJUnitRunner.class)
public class AuthenticationControllerMockTest {
    private static final String DOMAIN = "ws-test.sagebridge.org";
    private static final DateTime NOW = DateTime.now();
    private static final String REAUTH_TOKEN = "reauthToken";
    private static final String TEST_INTERNAL_SESSION_ID = "internal-session-id";
    private static final String TEST_PASSWORD = "password";
    private static final String TEST_ACCOUNT_ID = "spId";
    private static final String TEST_EMAIL = "email@email.com";
    private static final String TEST_SESSION_TOKEN = "session-token";
    private static final String TEST_STUDY_ID_STRING = "study-key";
    private static final StudyIdentifier TEST_STUDY_ID = new StudyIdentifierImpl(TEST_STUDY_ID_STRING);
    private static final String TEST_TOKEN = "verify-token";
    private static final SignIn EMAIL_PASSWORD_SIGN_IN_REQUEST = new SignIn.Builder().withStudy(TEST_STUDY_ID_STRING)
            .withEmail(TEST_EMAIL).withPassword(TEST_PASSWORD).build();
    private static final SignIn EMAIL_SIGN_IN_REQUEST = new SignIn.Builder().withStudy(TEST_STUDY_ID_STRING)
            .withEmail(TEST_EMAIL).withToken(TEST_TOKEN).build();
    private static final SignIn PHONE_SIGN_IN_REQUEST = new SignIn.Builder().withStudy(TEST_STUDY_ID_STRING)
            .withPhone(TestConstants.PHONE).build();
    private static final SignIn PHONE_SIGN_IN = new SignIn.Builder().withStudy(TEST_STUDY_ID_STRING)
            .withPhone(TestConstants.PHONE).withToken(TEST_TOKEN).build();

    @Spy
    AuthenticationController controller;

    @Mock
    AuthenticationService authenticationService;

    @Mock
    AccountWorkflowService accountWorkflowService;
    
    private Study study;
    
    @Mock
    StudyService studyService;
    
    @Mock
    CacheProvider cacheProvider;
    
    @Captor
    ArgumentCaptor<StudyParticipant> participantCaptor;
    
    @Captor
    ArgumentCaptor<RequestInfo> requestInfoCaptor;
    
    @Captor
    ArgumentCaptor<SignIn> signInCaptor;
    
    @Captor
    ArgumentCaptor<AccountId> accountIdCaptor;
    
    @Captor
    ArgumentCaptor<PasswordReset> passwordResetCaptor;

    @Captor
    ArgumentCaptor<CriteriaContext> contextCaptor;
    
    UserSession userSession;
    
    // This is manually mocked along with a request payload and captured in some tests
    // for verification
    Http.Response response;
    
    @Mock
    Metrics metrics;
    
    @Mock
    BridgeConfig mockConfig;
    
    @Before
    public void before() {
        DateTimeUtils.setCurrentMillisFixed(NOW.getMillis());
        
        // Mock the configuration so we can freeze the environment to one that requires SSL.
        when(mockConfig.get("domain")).thenReturn(DOMAIN);
        when(mockConfig.getEnvironment()).thenReturn(Environment.UAT);
        
        controller.setBridgeConfig(mockConfig);
        controller.setAuthenticationService(authenticationService);
        controller.setCacheProvider(cacheProvider);
        controller.setAccountWorkflowService(accountWorkflowService);
        
        userSession = new UserSession();
        userSession.setReauthToken(REAUTH_TOKEN);
        userSession.setSessionToken(TEST_SESSION_TOKEN);
        userSession.setParticipant(new StudyParticipant.Builder().withId(TEST_ACCOUNT_ID).build());
        userSession.setInternalSessionToken(TEST_INTERNAL_SESSION_ID);
        userSession.setStudyIdentifier(TEST_STUDY_ID);
        
        study = new DynamoStudy();
        study.setIdentifier(TEST_STUDY_ID_STRING);
        study.setDataGroups(TestConstants.USER_DATA_GROUPS);
        when(studyService.getStudy(TEST_STUDY_ID_STRING)).thenReturn(study);
        when(studyService.getStudy(TEST_STUDY_ID)).thenReturn(study);
        when(studyService.getStudy((String)null)).thenThrow(new EntityNotFoundException(Study.class));
        
        controller.setStudyService(studyService);
        
        doReturn(metrics).when(controller).getMetrics();
    }
    
    @After
    public void after() {
        DateTimeUtils.setCurrentMillisSystem();
    }

    @Test
    public void requestEmailSignIn() throws Exception {
        // Mock.
        TestUtils.mockPlay().withJsonBody(
                TestUtils.createJson("{'study':'study-key','email':'email@email.com'}")).mock();
        when(accountWorkflowService.requestEmailSignIn(any())).thenReturn(TEST_ACCOUNT_ID);

        // Execute.
        Result result = controller.requestEmailSignIn();
        assertResult(result, 202, "Email sent.");

        // Verify.
        verify(accountWorkflowService).requestEmailSignIn(signInCaptor.capture());
        assertEquals("study-key", signInCaptor.getValue().getStudyId());
        assertEquals(TEST_EMAIL, signInCaptor.getValue().getEmail());

        verify(metrics).setStudy(TEST_STUDY_ID_STRING);
        verify(metrics).setUserId(TEST_ACCOUNT_ID);
    }

    @Test
    public void requestEmailSignIn_NoUser() throws Exception {
        // Mock.
        TestUtils.mockPlay().withJsonBody(
                TestUtils.createJson("{'study':'study-key','email':'email@email.com'}")).mock();;
        when(accountWorkflowService.requestEmailSignIn(any())).thenReturn(null);

        // Execute.
        Result result = controller.requestEmailSignIn();
        assertResult(result, 202, "Email sent.");

        // Verify.
        verify(accountWorkflowService).requestEmailSignIn(signInCaptor.capture());
        assertEquals("study-key", signInCaptor.getValue().getStudyId());
        assertEquals(TEST_EMAIL, signInCaptor.getValue().getEmail());

        verify(metrics).setStudy(TEST_STUDY_ID_STRING);
        verify(metrics, never()).setUserId(any());
    }

    @Test
    public void emailSignIn() throws Exception {
        response = TestUtils.mockPlay().withMockResponse().withJsonBody(
                TestUtils.createJson("{'study':'study-key','email':'email@email.com','token':'ABC'}")).mock();
        userSession.setAuthenticated(true);
        study.setIdentifier("study-test");
        doReturn(userSession).when(authenticationService).emailSignIn(any(CriteriaContext.class), any(SignIn.class));
        
        Result result = controller.emailSignIn();
        assertResult(result, 200);
        JsonNode node = BridgeObjectMapper.get().readTree(Helpers.contentAsString(result));
        assertTrue(node.get("authenticated").booleanValue());
     
        verify(authenticationService).emailSignIn(any(CriteriaContext.class), signInCaptor.capture());
        
        SignIn captured = signInCaptor.getValue();
        assertEquals(TEST_EMAIL, captured.getEmail());
        assertEquals("study-key", captured.getStudyId());
        assertEquals("ABC", captured.getToken());
        
        verifyCommonLoggingForSignIns();
    }
    
    @Test(expected = BadRequestException.class)
    public void emailSignInMissingStudyId() throws Exception { 
        TestUtils.mockPlay().withJsonBody(
                TestUtils.createJson("{'email':'email@email.com','token':'abc'}")).mock();
        controller.emailSignIn();
    }

    @Test
    public void failedEmailSignInStillLogsStudyId() throws Exception {
        // Set up test.
        TestUtils.mockPlay().withBody(EMAIL_SIGN_IN_REQUEST).withMockResponse().mock();
        when(authenticationService.emailSignIn(any(), any())).thenThrow(EntityNotFoundException.class);

        // Execute.
        try {
            controller.emailSignIn();
            fail("expected exception");
        } catch (EntityNotFoundException ex) {
            // expected exception
        }

        // Verify metrics.
        verify(metrics).setStudy(TEST_STUDY_ID_STRING);
    }

    @Test(expected = BadRequestException.class)
    public void reauthenticateWithoutStudyThrowsException() throws Exception {
        TestUtils.mockPlay().withJsonBody(
                TestUtils.createJson("{'email':'email@email.com','reauthToken':'abc'}")).mock();
        
        controller.reauthenticate();
    }
    
    @Test
    public void reauthenticate() throws Exception {
        long timestamp = DateTime.now().getMillis();
        DateTimeUtils.setCurrentMillisFixed(timestamp);
        try {
            response = TestUtils.mockPlay().withMockResponse().withJsonBody(TestUtils.createJson(
                    "{'study':'study-key','email':'email@email.com','reauthToken':'abc'}")).mock();
            when(authenticationService.reauthenticate(any(), any(), any())).thenReturn(userSession);
            
            Result result = controller.reauthenticate();
            assertResult(result, 200);
            
            verify(authenticationService).reauthenticate(any(), any(), signInCaptor.capture());
            SignIn signIn = signInCaptor.getValue();
            assertEquals("study-key", signIn.getStudyId());
            assertEquals("email@email.com", signIn.getEmail());
            assertEquals("abc", signIn.getReauthToken());
            
            JsonNode node = BridgeObjectMapper.get().readTree(Helpers.contentAsString(result));
            assertEquals(REAUTH_TOKEN, node.get("reauthToken").textValue());
            
            verifyCommonLoggingForSignIns();
        } finally {
            DateTimeUtils.setCurrentMillisSystem();
        }
    }

    @Test
    public void failedReauthStillLogsStudyId() throws Exception {
        // Set up test.
        TestUtils.mockPlay().withMockResponse().withJsonBody(TestUtils.createJson(
                "{'study':'study-key','email':'email@email.com','reauthToken':'abc'}")).mock();
        when(authenticationService.reauthenticate(any(), any(), any())).thenThrow(EntityNotFoundException.class);

        // Execute.
        try {
            controller.reauthenticate();
            fail("expected exception");
        } catch (EntityNotFoundException ex) {
            // expected exception
        }

        // Verify metrics.
        verify(metrics).setStudy(TEST_STUDY_ID_STRING);
    }

    @Test
    public void getSessionIfItExistsNullToken() {
        doReturn(null).when(controller).getSessionToken();
        assertNull(controller.getSessionIfItExists());
    }

    @Test
    public void getSessionIfItExistsEmptyToken() {
        doReturn("").when(controller).getSessionToken();
        assertNull(controller.getSessionIfItExists());
    }

    @Test
    public void getSessionIfItExistsBlankToken() {
        doReturn("   ").when(controller).getSessionToken();
        assertNull(controller.getSessionIfItExists());
    }

    @Test
    public void getSessionIfItExistsSuccess() throws Exception {
        TestUtils.mockPlay().mock();
        // mock getSessionToken and getMetrics
        doReturn(TEST_SESSION_TOKEN).when(controller).getSessionToken();

        // mock AuthenticationService
        when(authenticationService.getSession(TEST_SESSION_TOKEN)).thenReturn(userSession);

        // execute and validate
        UserSession retVal = controller.getSessionIfItExists();
        assertSame(userSession, retVal);
        verifyMetrics();
    }

    @Test(expected = NotAuthenticatedException.class)
    public void getAuthenticatedSessionNullToken() {
        doReturn(null).when(controller).getSessionToken();
        controller.getAuthenticatedSession();
    }

    @Test(expected = NotAuthenticatedException.class)
    public void getAuthenticatedSessionEmptyToken() {
        doReturn("").when(controller).getSessionToken();
        controller.getAuthenticatedSession();
    }

    @Test(expected = NotAuthenticatedException.class)
    public void getAuthenticatedSessionBlankToken() {
        doReturn("   ").when(controller).getSessionToken();
        controller.getAuthenticatedSession();
    }

    @Test(expected = NotAuthenticatedException.class)
    public void getAuthenticatedSessionNullSession() {
        // mock getSessionToken and getMetrics
        doReturn(TEST_SESSION_TOKEN).when(controller).getSessionToken();

        // mock AuthenticationService
        when(authenticationService.getSession(TEST_SESSION_TOKEN)).thenReturn(null);

        // execute
        controller.getAuthenticatedSession();
    }

    @Test(expected = NotAuthenticatedException.class)
    public void getAuthenticatedSessionNotAuthenticated() {
        // mock getSessionToken and getMetrics
        doReturn(TEST_SESSION_TOKEN).when(controller).getSessionToken();

        // mock AuthenticationService
        UserSession session = createSession(TestConstants.REQUIRED_SIGNED_CURRENT, null);
        session.setAuthenticated(false);
        when(authenticationService.getSession(TEST_SESSION_TOKEN)).thenReturn(session);

        // execute
        controller.getAuthenticatedSession();
    }

    @Test
    public void getAuthenticatedSessionSuccess() throws Exception {
        TestUtils.mockPlay().mock();
        // mock getSessionToken and getMetrics
        doReturn(TEST_SESSION_TOKEN).when(controller).getSessionToken();

        // mock AuthenticationService
        UserSession session = createSession(TestConstants.REQUIRED_SIGNED_CURRENT, null);
        when(authenticationService.getSession(TEST_SESSION_TOKEN)).thenReturn(session);

        // execute and validate
        UserSession retVal = controller.getAuthenticatedSession();
        assertSame(session, retVal);
        verifyMetrics();
    }

    @Test
    public void signUpWithCompleteUserData() throws Exception {
        // Other fields will be passed along to the PartcipantService, but it will not be utilized
        // These are the fields that *can* be changed. They are all passed along.
        StudyParticipant originalParticipant = TestUtils.getStudyParticipant(AuthenticationControllerMockTest.class);
        ObjectNode node = BridgeObjectMapper.get().valueToTree(originalParticipant);
        node.put("study", TEST_STUDY_ID_STRING);
        
        TestUtils.mockPlay().withJsonBody(node.toString()).withMockResponse().mock();
        
        Result result = controller.signUp();
        assertResult(result, 201, "Signed up.");
        
        verify(authenticationService).signUp(eq(study), participantCaptor.capture());
        
        StudyParticipant persistedParticipant = participantCaptor.getValue();
        assertEquals(originalParticipant.getFirstName(), persistedParticipant.getFirstName());
        assertEquals(originalParticipant.getLastName(), persistedParticipant.getLastName());
        assertEquals(originalParticipant.getEmail(), persistedParticipant.getEmail());
        assertEquals(originalParticipant.getPassword(), persistedParticipant.getPassword());
        assertEquals(originalParticipant.getSharingScope(), persistedParticipant.getSharingScope());
        assertEquals(originalParticipant.getExternalId(), persistedParticipant.getExternalId());
        assertTrue(persistedParticipant.isNotifyByEmail());
        assertEquals(originalParticipant.getDataGroups(), persistedParticipant.getDataGroups());
        assertEquals(originalParticipant.getAttributes(), persistedParticipant.getAttributes());
        assertEquals(originalParticipant.getLanguages(), persistedParticipant.getLanguages());

        // Verify metrics.
        verify(metrics).setStudy(TEST_STUDY_ID_STRING);
    }

    @Test(expected = UnsupportedVersionException.class)
    public void signUpAppVersionDisabled() throws Exception {
        // Participant
        StudyParticipant originalParticipant = TestUtils.getStudyParticipant(AuthenticationControllerMockTest.class);
        ObjectNode node = BridgeObjectMapper.get().valueToTree(originalParticipant);
        node.put("study", TEST_STUDY_ID_STRING);

        // min app version is 20 (which is higher than 14)
        study.getMinSupportedAppVersions().put(OperatingSystem.IOS, 20);

        // Setup and execute. This will throw.
        response = TestUtils.mockPlay().withJsonBody(node.toString())
                .withHeader("User-Agent", "App/14 (Unknown iPhone; iOS/9.0.2) BridgeSDK/4").mock();
        controller.signUp();
    }

    @Test(expected = EntityNotFoundException.class)
    public void signUpNoStudy() throws Exception {
        // Participant - don't add study
        StudyParticipant originalParticipant = TestUtils.getStudyParticipant(AuthenticationControllerMockTest.class);
        ObjectNode node = BridgeObjectMapper.get().valueToTree(originalParticipant);

        // Setup and execute. This will throw.
        TestUtils.mockPlay().withJsonBody(node.toString()).mock();
        controller.signUp();
    }

    @SuppressWarnings({ "static-access", "deprecation" })
    private void signInNewSession(boolean isConsented, Roles role) throws Exception {
        // Even if a session token already exists, we still ignore it and call signIn anyway.
        doReturn(TEST_CONTEXT).when(controller).getCriteriaContext(any(StudyIdentifier.class));

        // mock request
        String requestJsonString = "{\n" +
                "   \"email\":\"" + TEST_EMAIL + "\",\n" +
                "   \"password\":\"" + TEST_PASSWORD + "\",\n" +
                "   \"study\":\"" + TEST_STUDY_ID_STRING + "\"\n" +
                "}";

        response = TestUtils.mockPlay().withJsonBody(requestJsonString).withMockResponse().mock();

        // mock AuthenticationService
        ConsentStatus consentStatus = (isConsented) ? TestConstants.REQUIRED_SIGNED_CURRENT : null;
        UserSession session = createSession(consentStatus, role);
        when(authenticationService.signIn(any(), any(), any())).thenReturn(session);
        
        // execute and validate
        Result result = controller.signInV3();
        assertSessionInPlayResult(result);
        
        controller.response();

        verify(cacheProvider).updateRequestInfo(requestInfoCaptor.capture());
        RequestInfo requestInfo = requestInfoCaptor.getValue();
        assertEquals("spId", requestInfo.getUserId());
        assertEquals(TEST_STUDY_ID, requestInfo.getStudyIdentifier());
        assertTrue(requestInfo.getSignedInOn() != null);
        assertEquals(TestConstants.USER_DATA_GROUPS, requestInfo.getUserDataGroups());
        assertNotNull(requestInfo.getSignedInOn());
        verifyCommonLoggingForSignIns();

        // validate signIn
        ArgumentCaptor<SignIn> signInCaptor = ArgumentCaptor.forClass(SignIn.class);
        verify(authenticationService).signIn(same(study), any(), signInCaptor.capture());

        SignIn signIn = signInCaptor.getValue();
        assertEquals(TEST_EMAIL, signIn.getEmail());
        assertEquals(TEST_PASSWORD, signIn.getPassword());
    }

    @Test
    public void signInNewSession() throws Exception {
        signInNewSession(true, null);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void signOut() throws Exception {
        TestUtils.mockPlay().withMockResponse().mock();
        
        // mock getSessionToken and getMetrics
        doReturn(TEST_SESSION_TOKEN).when(controller).getSessionToken();

        // mock AuthenticationService
        UserSession session = createSession(TestConstants.REQUIRED_SIGNED_CURRENT, null);
        when(authenticationService.getSession(TEST_SESSION_TOKEN)).thenReturn(session);

        // execute and validate
        Result result = controller.signOut();
        assertResult(result, 200);
        
        @SuppressWarnings("static-access")
        Http.Response mockResponse = controller.response();

        verify(authenticationService).signOut(session);
        verify(mockResponse).discardCookie(BridgeConstants.SESSION_TOKEN_HEADER);
        verifyMetrics();
    }
    
    @Test
    public void signOutV4() throws Exception {
        TestUtils.mockPlay().withMockResponse().mock();
        
        // mock getSessionToken and getMetrics
        doReturn(TEST_SESSION_TOKEN).when(controller).getSessionToken();

        // mock AuthenticationService
        UserSession session = createSession(TestConstants.REQUIRED_SIGNED_CURRENT, null);
        when(authenticationService.getSession(TEST_SESSION_TOKEN)).thenReturn(session);

        // execute and validate
        Result result = controller.signOutV4();
        assertResult(result, 200);
        
        @SuppressWarnings("static-access")
        Http.Response mockResponse = controller.response();

        verify(authenticationService).signOut(session);
        verify(mockResponse).discardCookie(BridgeConstants.SESSION_TOKEN_HEADER);
        verify(mockResponse).setHeader(BridgeConstants.CLEAR_SITE_DATA_HEADER, BridgeConstants.CLEAR_SITE_DATA_VALUE);
        verifyMetrics();
    }
    
    @Test
    public void signOutV4Throws() throws Exception {
        TestUtils.mockPlay().withMockResponse().mock();
        
        // mock getSessionToken and getMetrics
        doReturn(null).when(controller).getSessionToken();

        // execute and validate
        try {
            controller.signOutV4();
            fail("Should have thrown exception");
        } catch(BadRequestException e) {
            
        }
        
        @SuppressWarnings("static-access")
        Http.Response mockResponse = controller.response();
        verify(mockResponse).discardCookie(BridgeConstants.SESSION_TOKEN_HEADER);
        verify(mockResponse).setHeader(BridgeConstants.CLEAR_SITE_DATA_HEADER, BridgeConstants.CLEAR_SITE_DATA_VALUE);
        // We do not send metrics if you don't have a session, for better or worse.
    }

    @SuppressWarnings("deprecation")
    @Test
    public void signOutAlreadySignedOut() throws Exception {
        TestUtils.mockPlay().withMockResponse().mock();
        
        // mock getSessionToken and getMetrics
        doReturn(null).when(controller).getSessionToken();

        // execute and validate
        Result result = controller.signOut();
        assertResult(result, 200);

        // No session, so no check on metrics or AuthService.signOut()
    }

    @Test
    public void verifyEmail() throws Exception {
        // mock request
        String json = TestUtils.createJson(
                "{'sptoken':'"+TEST_TOKEN+"','study':'"+TEST_STUDY_ID_STRING+"'}");
        TestUtils.mockPlay().withJsonBody(json).mock();

        ArgumentCaptor<Verification> verificationCaptor = ArgumentCaptor.forClass(Verification.class);

        // execute and validate
        Result result = controller.verifyEmail();
        TestUtils.assertResult(result, 200, "Email address verified.");

        // validate email verification
        verify(authenticationService).verifyChannel(eq(ChannelType.EMAIL), verificationCaptor.capture());
        Verification verification = verificationCaptor.getValue();
        assertEquals(TEST_TOKEN, verification.getSptoken());
    }
    
    @Test
    public void verifyPhone() throws Exception {
        // mock request
        String json = TestUtils.createJson(
                "{'sptoken':'"+TEST_TOKEN+"','study':'"+TEST_STUDY_ID_STRING+"'}");
        TestUtils.mockPlay().withJsonBody(json).mock();

        ArgumentCaptor<Verification> verificationCaptor = ArgumentCaptor.forClass(Verification.class);

        // execute and validate
        Result result = controller.verifyPhone();
        TestUtils.assertResult(result, 200, "Phone number verified.");

        // validate phone verification
        verify(authenticationService).verifyChannel(eq(ChannelType.PHONE), verificationCaptor.capture());
        Verification verification = verificationCaptor.getValue();
        assertEquals(TEST_TOKEN, verification.getSptoken());
    }
    
    @SuppressWarnings("deprecation")
    @Test(expected = UnsupportedVersionException.class)
    public void signInBlockedByVersionKillSwitch() throws Exception {
        String json = TestUtils.createJson(
                "{'study':'" + TEST_STUDY_ID_STRING + 
                "','email':'email@email.com','password':'bar'}");
        TestUtils.mockPlay().withJsonBody(json)
            .withHeader("User-Agent", "App/14 (Unknown iPhone; iOS/9.0.2) BridgeSDK/4").mock();

        study.getMinSupportedAppVersions().put(OperatingSystem.IOS, 20);
        
        controller.signInV3();
    }
    
    @Test
    public void localSignInSetsSessionCookie() throws Exception {
        when(mockConfig.getEnvironment()).thenReturn(Environment.LOCAL);
        
        doReturn(TEST_CONTEXT).when(controller).getCriteriaContext(any(StudyIdentifier.class));

        // mock request
        String requestJsonString = "{" +
                "\"email\":\"" + TEST_EMAIL + "\"," +
                "\"password\":\"" + TEST_PASSWORD + "\"," +
                "\"study\":\"" + TEST_STUDY_ID_STRING + "\"}";

        response = TestUtils.mockPlay().withJsonBody(requestJsonString).withMockResponse().mock();

        // mock AuthenticationService
        UserSession session = createSession(TestConstants.REQUIRED_SIGNED_CURRENT, null);
        when(authenticationService.signIn(any(), any(), any())).thenReturn(session);
        
        // execute and validate
        controller.signIn();

        verify(response).setCookie(BridgeConstants.SESSION_TOKEN_HEADER, TEST_SESSION_TOKEN,
                BridgeConstants.BRIDGE_SESSION_EXPIRE_IN_SECONDS, "/", DOMAIN, false, false);
    }
    
    @Test
    public void signInOnLocalDoesNotSetCookieWithSSL() throws Exception {
        String json = TestUtils.createJson(
                "{'study':'" + TEST_STUDY_ID_STRING + 
                "','email':'email@email.com','password':'bar'}");
        response = TestUtils.mockPlay().withJsonBody(json).withMockResponse().mock();
        when(controller.bridgeConfig.getEnvironment()).thenReturn(Environment.LOCAL);
        
        UserSession session = createSession(null, null);
        when(authenticationService.signIn(any(), any(), any())).thenReturn(session);
        
        controller.signIn();
        
        verify(response).setCookie(BridgeConstants.SESSION_TOKEN_HEADER, TEST_SESSION_TOKEN,
                BridgeConstants.BRIDGE_SESSION_EXPIRE_IN_SECONDS, "/", DOMAIN, false, false);
    }
    
    @Test(expected = UnsupportedVersionException.class)
    public void emailSignInBlockedByVersionKillSwitch() throws Exception {
        String json = TestUtils.createJson(
                "{'study':'" + TEST_STUDY_ID_STRING + 
                "','email':'email@email.com','password':'bar'}");
        TestUtils.mockPlay().withJsonBody(json)
            .withHeader("User-Agent", "App/14 (Unknown iPhone; iOS/9.0.2) BridgeSDK/4").mock();
        study.getMinSupportedAppVersions().put(OperatingSystem.IOS, 20);
        
        controller.emailSignIn();
    }
    
    @Test(expected = UnsupportedVersionException.class)
    public void phoneSignInBlockedByVersionKillSwitch() throws Exception {
        String json = TestUtils.createJson(
                "{'study':'" + TEST_STUDY_ID_STRING + 
                "','email':'email@email.com','password':'bar'}");
        TestUtils.mockPlay().withJsonBody(json)
            .withHeader("User-Agent", "App/14 (Unknown iPhone; iOS/9.0.2) BridgeSDK/4").mock();
        study.getMinSupportedAppVersions().put(OperatingSystem.IOS, 20);
        
        controller.phoneSignIn();
    }
    
    @Test
    public void resendEmailVerificationWorks() throws Exception {
        mockSignInWithEmailPayload();
        study.getMinSupportedAppVersions().put(OperatingSystem.IOS, 0);
        
        controller.resendEmailVerification();
        
        verify(authenticationService).resendVerification(eq(ChannelType.EMAIL), accountIdCaptor.capture());
        AccountId deser = accountIdCaptor.getValue();
        assertEquals(TEST_STUDY_ID.getIdentifier(), deser.getStudyId());
        assertEquals(TEST_EMAIL, deser.getEmail());
    }
    
    @Test(expected = UnsupportedVersionException.class)
    public void resendEmailVerificationAppVersionDisabled() throws Exception {
        mockSignInWithEmailPayload();
        study.getMinSupportedAppVersions().put(OperatingSystem.IOS, 20);
        
        controller.resendEmailVerification();
    }

    @Test(expected = EntityNotFoundException.class)
    public void resendEmailVerificationNoStudy() throws Exception {
        String json = TestUtils.createJson("{'email':'email@email.com'}");
        TestUtils.mockPlay().withJsonBody(json).mock();
        controller.resendEmailVerification();
    }

    @Test
    public void resendPhoneVerificationWorks() throws Exception {
        mockSignInWithPhonePayload();
        study.getMinSupportedAppVersions().put(OperatingSystem.IOS, 0);
        
        controller.resendPhoneVerification();
        
        verify(authenticationService).resendVerification(eq(ChannelType.PHONE), accountIdCaptor.capture());
        AccountId deser = accountIdCaptor.getValue();
        assertEquals(TEST_STUDY_ID.getIdentifier(), deser.getStudyId());
        assertEquals(TestConstants.PHONE, deser.getPhone());
    }
    
    @Test(expected = UnsupportedVersionException.class)
    public void resendPhoneVerificationAppVersionDisabled() throws Exception {
        mockSignInWithPhonePayload();
        study.getMinSupportedAppVersions().put(OperatingSystem.IOS, 20);
        
        controller.resendPhoneVerification();
    }

    @Test(expected = EntityNotFoundException.class)
    public void resendPhoneVerificationNoStudy() throws Exception {
        String json = TestUtils.createJson("{'phone':{'number':'4082588569','regionCode':'US'}}");
        TestUtils.mockPlay().withJsonBody(json).mock();
        controller.resendPhoneVerification();
    }
    
    @Test
    public void resendPhoneVerificationVerifyPhone() throws Exception {
        String json = TestUtils.createJson("{'study':'study-key','phone':{'number':'4082588569','regionCode':'US'}}");
        TestUtils.mockPlay().withJsonBody(json).withMockResponse().mock();
        controller.resendPhoneVerification();
    }
    
    @Test
    public void resetPassword() throws Exception {
        mockResetPasswordRequest();
        study.getMinSupportedAppVersions().put(OperatingSystem.IOS, 0);
        
        controller.resetPassword();
        
        verify(authenticationService).resetPassword(passwordResetCaptor.capture());
        
        PasswordReset passwordReset = passwordResetCaptor.getValue();
        assertEquals("aSpToken", passwordReset.getSptoken());
        assertEquals("aPassword", passwordReset.getPassword());
        assertEquals(TEST_STUDY_ID_STRING, passwordReset.getStudyIdentifier());
    }
    
    @Test(expected = UnsupportedVersionException.class)
    public void resetPasswordAppVersionDisabled() throws Exception {
        mockResetPasswordRequest();
        study.getMinSupportedAppVersions().put(OperatingSystem.IOS, 20);
        
        controller.resetPassword();
    }

    @Test(expected = EntityNotFoundException.class)
    public void resetPasswordNoStudy() throws Exception {
        TestUtils.mockPlay().withBody(new PasswordReset("aPassword", "aSpToken", null)).mock();
        controller.resetPassword();
    }

    private void mockResetPasswordRequest() throws Exception {
        String json = TestUtils.createJson("{'study':'" + TEST_STUDY_ID_STRING + 
            "','sptoken':'aSpToken','password':'aPassword'}");
        TestUtils.mockPlay().withJsonBody(json)
            .withHeader("User-Agent", "App/14 (Unknown iPhone; iOS/9.0.2) BridgeSDK/4").mock();
    }
    
    @Test
    public void requestResetPasswordWithEmail() throws Exception {
        mockSignInWithEmailPayload();
        study.getMinSupportedAppVersions().put(OperatingSystem.IOS, 0);
        
        controller.requestResetPassword();
        
        verify(authenticationService).requestResetPassword(eq(study), eq(false), signInCaptor.capture());
        SignIn deser = signInCaptor.getValue();
        assertEquals(TEST_STUDY_ID_STRING, deser.getStudyId());
        assertEquals(TEST_EMAIL, deser.getEmail());
    }
    
    @Test
    public void requestResetPasswordWithPhone() throws Exception {
        mockSignInWithPhonePayload();
        study.getMinSupportedAppVersions().put(OperatingSystem.IOS, 0);
        
        controller.requestResetPassword();
        
        verify(authenticationService).requestResetPassword(eq(study), eq(false), signInCaptor.capture());
        SignIn deser = signInCaptor.getValue();
        assertEquals(TEST_STUDY_ID_STRING, deser.getStudyId());
        assertEquals(TestConstants.PHONE.getNumber(), deser.getPhone().getNumber());
    }
    
    @Test(expected = UnsupportedVersionException.class)
    public void requestResetPasswordAppVersionDisabled() throws Exception {
        mockSignInWithEmailPayload();
        study.getMinSupportedAppVersions().put(OperatingSystem.IOS, 20); // blocked
        
        controller.requestResetPassword();
    }

    @Test(expected = EntityNotFoundException.class)
    public void requestResetPasswordNoStudy() throws Exception {
        when(studyService.getStudy((String) any())).thenThrow(new EntityNotFoundException(Study.class));
        TestUtils.mockPlay().withBody(new SignIn.Builder().withEmail(TEST_EMAIL).build()).mock();
        
        controller.requestResetPassword();
    }
    
    @Test
    public void signUpWithNoCheckForConsentDeclared() throws Exception {
        StudyParticipant participant = new StudyParticipant.Builder()
                .withEmail(TEST_EMAIL).withPassword(TEST_PASSWORD).build();
        
        ObjectNode node = (ObjectNode)BridgeObjectMapper.get().valueToTree(participant);
        node.put("study", TEST_STUDY_ID_STRING);
        TestUtils.mockPlay().withJsonBody(node.toString()).withMockResponse().mock();
        
        Result result = controller.signUp();
        TestUtils.assertResult(result, 201, "Signed up.");
        
        verify(authenticationService).signUp(eq(study), participantCaptor.capture());
        StudyParticipant captured = participantCaptor.getValue();
        assertEquals(TEST_EMAIL, captured.getEmail());
        assertEquals(TEST_PASSWORD, captured.getPassword());
    }

    @Test
    public void signUpWithCheckForConsentDeclaredFalse() throws Exception {
        StudyParticipant participant = new StudyParticipant.Builder()
                .withEmail(TEST_EMAIL).withPassword(TEST_PASSWORD).build();
        
        ObjectNode node = (ObjectNode)BridgeObjectMapper.get().valueToTree(participant);
        node.put("study", TEST_STUDY_ID_STRING);
        node.put("checkForConsent", false);
        TestUtils.mockPlay().withJsonBody(node.toString()).withMockResponse().mock();
        
        Result result = controller.signUp();
        TestUtils.assertResult(result, 201, "Signed up.");
        
        verify(authenticationService).signUp(eq(study), participantCaptor.capture());
        StudyParticipant captured = participantCaptor.getValue();
        assertEquals(TEST_EMAIL, captured.getEmail());
        assertEquals(TEST_PASSWORD, captured.getPassword());
    }
    
    @Test
    public void signUpWithCheckForConsentDeclaredTrue() throws Exception {
        StudyParticipant participant = new StudyParticipant.Builder()
                .withEmail(TEST_EMAIL).withPassword(TEST_PASSWORD).build();
        
        ObjectNode node = (ObjectNode)BridgeObjectMapper.get().valueToTree(participant);
        node.put("study", TEST_STUDY_ID_STRING);
        node.put("checkForConsent", true);
        TestUtils.mockPlay().withJsonBody(node.toString()).withMockResponse().mock();
        
        Result result = controller.signUp();
        TestUtils.assertResult(result, 201, "Signed up.");
        
        verify(authenticationService).signUp(eq(study), participantCaptor.capture());
        StudyParticipant captured = participantCaptor.getValue();
        assertEquals(TEST_EMAIL, captured.getEmail());
        assertEquals(TEST_PASSWORD, captured.getPassword());
    }

    @Test
    public void requestPhoneSignIn() throws Exception {
        // Mock.
        TestUtils.mockPlay().withBody(PHONE_SIGN_IN_REQUEST).mock();
        when(accountWorkflowService.requestPhoneSignIn(any())).thenReturn(TEST_ACCOUNT_ID);

        // Execute.
        Result result = controller.requestPhoneSignIn();
        assertResult(result, 202, "Message sent.");

        // Verify.
        verify(accountWorkflowService).requestPhoneSignIn(signInCaptor.capture());

        SignIn captured = signInCaptor.getValue();
        assertEquals(TEST_STUDY_ID_STRING, captured.getStudyId());
        assertEquals(TestConstants.PHONE.getNumber(), captured.getPhone().getNumber());

        verify(metrics).setStudy(TEST_STUDY_ID_STRING);
        verify(metrics).setUserId(TEST_ACCOUNT_ID);
    }

    @Test
    public void requestPhoneSignIn_NoUser() throws Exception {
        // Mock.
        TestUtils.mockPlay().withBody(PHONE_SIGN_IN_REQUEST).mock();
        when(accountWorkflowService.requestPhoneSignIn(any())).thenReturn(null);

        // Execute.
        Result result = controller.requestPhoneSignIn();
        assertResult(result, 202, "Message sent.");

        // Verify.
        verify(accountWorkflowService).requestPhoneSignIn(signInCaptor.capture());

        SignIn captured = signInCaptor.getValue();
        assertEquals(TEST_STUDY_ID_STRING, captured.getStudyId());
        assertEquals(TestConstants.PHONE.getNumber(), captured.getPhone().getNumber());

        verify(metrics).setStudy(TEST_STUDY_ID_STRING);
        verify(metrics, never()).setUserId(any());
    }

    @Test
    public void phoneSignIn() throws Exception {
        response = TestUtils.mockPlay().withBody(PHONE_SIGN_IN).withMockResponse().mock();
        
        when(authenticationService.phoneSignIn(any(), any())).thenReturn(userSession);
        
        Result result = controller.phoneSignIn();
        assertResult(result, 200);
        
        // Returns user session.
        JsonNode node = TestUtils.getJson(result);
        assertEquals(TEST_SESSION_TOKEN, node.get("sessionToken").textValue());
        assertEquals("UserSessionInfo", node.get("type").textValue());
        
        verify(authenticationService).phoneSignIn(contextCaptor.capture(), signInCaptor.capture());
        
        CriteriaContext context = contextCaptor.getValue();
        assertEquals(TEST_STUDY_ID_STRING, context.getStudyIdentifier().getIdentifier());
        
        SignIn captured = signInCaptor.getValue();
        assertEquals(TEST_STUDY_ID_STRING, captured.getStudyId());
        assertEquals(TEST_TOKEN, captured.getToken());
        assertEquals(TestConstants.PHONE.getNumber(), captured.getPhone().getNumber());
        
        verifyCommonLoggingForSignIns();
    }
    
    @Test(expected = BadRequestException.class)
    public void phoneSignInMissingStudy() throws Exception {
        SignIn badPhoneSignIn = new SignIn.Builder().withStudy(null)
                .withPhone(TestConstants.PHONE).withToken(TEST_TOKEN).build();
        TestUtils.mockPlay().withBody(badPhoneSignIn).mock();
        
        controller.phoneSignIn();
    }
    
    @Test(expected = EntityNotFoundException.class)
    public void phoneSignInBadStudy() throws Exception {
        SignIn badPhoneSignIn = new SignIn.Builder().withStudy("bad-study")
                .withPhone(TestConstants.PHONE).withToken(TEST_TOKEN).build();
        TestUtils.mockPlay().withBody(badPhoneSignIn).mock();
        when(studyService.getStudy((String)any())).thenThrow(new EntityNotFoundException(Study.class));
        
        controller.phoneSignIn();
    }

    @Test
    public void failedPhoneSignInStillLogsStudyId() throws Exception {
        // Set up test.
        TestUtils.mockPlay().withBody(PHONE_SIGN_IN).withMockResponse().mock();
        when(authenticationService.phoneSignIn(any(), any())).thenThrow(EntityNotFoundException.class);

        // Execute.
        try {
            controller.phoneSignIn();
            fail("expected exception");
        } catch (EntityNotFoundException ex) {
            // expected exception
        }

        // Verify metrics.
        verify(metrics).setStudy(TEST_STUDY_ID_STRING);
    }

    @SuppressWarnings("deprecation")
    @Test(expected = EntityNotFoundException.class)
    public void signInV3ThrowsNotFound() throws Exception {
        TestUtils.mockPlay().withBody(PHONE_SIGN_IN).withMockResponse().mock();
        
        when(authenticationService.signIn(any(), any(), any())).thenThrow(new UnauthorizedException());
        
        controller.signInV3();
    }

    @SuppressWarnings("deprecation")
    @Test
    public void failedSignInV3StillLogsStudyId() throws Exception {
        // Set up test.
        TestUtils.mockPlay().withBody(EMAIL_PASSWORD_SIGN_IN_REQUEST).withMockResponse().mock();
        when(authenticationService.signIn(any(), any(), any())).thenThrow(EntityNotFoundException.class);

        // Execute.
        try {
            controller.signInV3();
            fail("expected exception");
        } catch (EntityNotFoundException ex) {
            // expected exception
        }

        // Verify metrics.
        verify(metrics).setStudy(TEST_STUDY_ID_STRING);
    }

    @Test(expected = UnauthorizedException.class)
    public void signInV4ThrowsUnauthoried() throws Exception {
        TestUtils.mockPlay().withBody(PHONE_SIGN_IN).withMockResponse().mock();
        
        when(authenticationService.signIn(any(), any(), any())).thenThrow(new UnauthorizedException());
        
        controller.signIn();
    }

    @Test
    public void failedSignInV4StillLogsStudyId() throws Exception {
        // Set up test.
        TestUtils.mockPlay().withBody(EMAIL_PASSWORD_SIGN_IN_REQUEST).withMockResponse().mock();
        when(authenticationService.signIn(any(), any(), any())).thenThrow(EntityNotFoundException.class);

        // Execute.
        try {
            controller.signIn();
            fail("expected exception");
        } catch (EntityNotFoundException ex) {
            // expected exception
        }

        // Verify metrics.
        verify(metrics).setStudy(TEST_STUDY_ID_STRING);
    }

    @Test
    public void unconsentedSignInSetsCookie() throws Exception {
        response = TestUtils.mockPlay().withBody(EMAIL_PASSWORD_SIGN_IN_REQUEST).withMockResponse().mock();
        when(authenticationService.signIn(any(), any(), any())).thenThrow(new ConsentRequiredException(userSession));
        
        try {
            controller.signIn();
            fail("Should have thrown exeption");
        } catch(ConsentRequiredException e) {
        }
        verifyCommonLoggingForSignIns();
    }
    
    @Test
    public void unconsentedEmailSignInSetsCookie() throws Exception {
        response = TestUtils.mockPlay().withBody(EMAIL_SIGN_IN_REQUEST).withMockResponse().mock();
        when(authenticationService.emailSignIn(any(), any())).thenThrow(new ConsentRequiredException(userSession));
        
        try {
            controller.emailSignIn();
            fail("Should have thrown exeption");
        } catch(ConsentRequiredException e) {
        }
        verifyCommonLoggingForSignIns();
    }
    
    @Test
    public void unconsentedPhoneSignInSetsCookie() throws Exception {
        response = TestUtils.mockPlay().withBody(PHONE_SIGN_IN_REQUEST).withMockResponse().mock();
        when(authenticationService.phoneSignIn(any(), any())).thenThrow(new ConsentRequiredException(userSession));
        
        try {
            controller.phoneSignIn();
            fail("Should have thrown exeption");
        } catch(ConsentRequiredException e) {
        }
        verifyCommonLoggingForSignIns();
    }
    
    private void mockSignInWithEmailPayload() throws Exception {
        SignIn signIn = new SignIn.Builder().withStudy(TEST_STUDY_ID_STRING).withEmail(TEST_EMAIL).build();
        TestUtils.mockPlay().withBody(signIn)
                .withHeader("User-Agent", "App/14 (Unknown iPhone; iOS/9.0.2) BridgeSDK/4").mock();
    }

    private void mockSignInWithPhonePayload() throws Exception {
        SignIn signIn = new SignIn.Builder().withStudy(TEST_STUDY_ID_STRING).withPhone(TestConstants.PHONE).build();
        TestUtils.mockPlay().withBody(signIn)
            .withHeader("User-Agent", "App/14 (Unknown iPhone; iOS/9.0.2) BridgeSDK/4").mock();
    }

    private static void assertSessionInPlayResult(Result result) throws Exception {
        assertResult(result, 200);
        // test only a few key values
        String resultString = Helpers.contentAsString(result);
        JsonNode resultNode = BridgeObjectMapper.get().readTree(resultString);
        assertTrue(resultNode.get("authenticated").booleanValue());
        assertEquals(TEST_SESSION_TOKEN, resultNode.get("sessionToken").textValue());
    }

    private UserSession createSession(ConsentStatus status, Roles role) {
        StudyParticipant.Builder builder = new StudyParticipant.Builder();
        builder.withId(TEST_ACCOUNT_ID);
        // set this value so we can verify it is copied into RequestInfo on a sign in.
        builder.withDataGroups(TestConstants.USER_DATA_GROUPS);
        if (role != null) {
            builder.withRoles(Sets.newHashSet(role));
        }
        UserSession session = new UserSession(builder.build());
        session.setAuthenticated(true);
        session.setInternalSessionToken(TEST_INTERNAL_SESSION_ID);
        session.setSessionToken(TEST_SESSION_TOKEN);
        session.setStudyIdentifier(TEST_STUDY_ID);
        if (status != null){
            session.setConsentStatuses(TestUtils.toMap(status));    
        }
        return session;
    }
    
    private void verifyMetrics() {
        verify(controller, atLeastOnce()).getMetrics();
        
        verify(metrics, atLeastOnce()).setSessionId(TEST_INTERNAL_SESSION_ID);
        verify(metrics, atLeastOnce()).setUserId(TEST_ACCOUNT_ID);
        verify(metrics, atLeastOnce()).setStudy(TEST_STUDY_ID_STRING);
    }
    
    private void verifyCommonLoggingForSignIns() throws Exception {
        verifyMetrics();
        verify(cacheProvider).updateRequestInfo(requestInfoCaptor.capture());
        verify(response, never()).setCookie(any(), any(), anyInt(), any(), any(), anyBoolean(), anyBoolean());        
        RequestInfo info = requestInfoCaptor.getValue();
        assertEquals(NOW.getMillis(), info.getSignedInOn().getMillis());
    }
    
}
