package org.sagebionetworks.bridge.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.sagebionetworks.bridge.TestConstants.TEST_CONTEXT;
import static org.sagebionetworks.bridge.TestConstants.TEST_STUDY_IDENTIFIER;
import static org.sagebionetworks.bridge.dao.ParticipantOption.DATA_GROUPS;
import static org.sagebionetworks.bridge.dao.ParticipantOption.LANGUAGES;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import org.sagebionetworks.bridge.DefaultStudyBootstrapper;
import org.sagebionetworks.bridge.Roles;
import org.sagebionetworks.bridge.TestUserAdminHelper;
import org.sagebionetworks.bridge.TestUserAdminHelper.TestUser;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.dao.ParticipantOption;
import org.sagebionetworks.bridge.dao.ParticipantOption.SharingScope;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.CriteriaContext;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.ConsentStatus;
import org.sagebionetworks.bridge.models.accounts.Email;
import org.sagebionetworks.bridge.models.accounts.IdentifierHolder;
import org.sagebionetworks.bridge.models.accounts.PasswordReset;
import org.sagebionetworks.bridge.models.accounts.SignIn;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.accounts.UserSessionInfo;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.subpopulations.SubpopulationGuid;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Sets;

@ContextConfiguration("classpath:test-context.xml")
@RunWith(SpringJUnit4ClassRunner.class)
public class AuthenticationServiceTest {
    
    private static final Set<Roles> CALLER_ROLES = Sets.newHashSet();
    private static final Set<String> ORIGINAL_DATA_GROUPS = Sets.newHashSet("group1");
    private static final Set<String> UPDATED_DATA_GROUPS = Sets.newHashSet("sdk-int-1","sdk-int-2","group1");
    
    @Resource
    private CacheProvider cacheProvider;

    @Resource
    private AuthenticationService authService;

    @Resource
    private ParticipantOptionsService optionsService;
    
    @Resource
    private AccountDao accountDao;
    
    @Resource
    private StudyService studyService;

    @Resource
    private TestUserAdminHelper helper;
    
    @Resource
    private SubpopulationService subpopService;
    
    @Resource
    private ParticipantService participantService;
    
    @Resource
    private UserAdminService userAdminService;
    
    @Resource
    private AccountWorkflowService accountWorkflowService;
    
    private Study study;
    
    private TestUser testUser;
    
    @Before
    public void before() {
        study = studyService.getStudy("api");
    }
    
    @After
    public void after() {
        if (testUser != null && testUser.getId() != null) {
            helper.deleteUser(testUser);
        }
    }
    
    private void initTestUser() {
        testUser = helper.getBuilder(AuthenticationServiceTest.class).build();
    }

    @Test(expected = BridgeServiceException.class)
    public void signInNoEmail() throws Exception {
        authService.signIn(study, TEST_CONTEXT, new SignIn.Builder().withStudyId(study.getIdentifier()).withPassword("bar").build());
    }

    @Test(expected = BridgeServiceException.class)
    public void signInNoPassword() throws Exception {
        authService.signIn(study, TEST_CONTEXT, new SignIn.Builder().withStudyId(study.getIdentifier()).withEmail("foobar").build());
    }

    @Test(expected = EntityNotFoundException.class)
    public void signInInvalidCredentials() throws Exception {
        authService.signIn(study, TEST_CONTEXT, new SignIn.Builder().withStudyId(study.getIdentifier()).withEmail("foobar").withPassword("bar").build());
    }

    @Test
    public void signInCorrectCredentials() throws Exception {
        initTestUser();
        UserSession newSession = authService.getSession(testUser.getSessionToken());
        assertEquals("Email is for test2 user", newSession.getParticipant().getEmail(), testUser.getEmail());
        assertTrue("Session token has been assigned", StringUtils.isNotBlank(testUser.getSessionToken()));
    }

    @Test
    public void signInWhenSignedIn() throws Exception {
        initTestUser();
        String sessionToken = testUser.getSessionToken();
        UserSession newSession = authService.signIn(testUser.getStudy(), TEST_CONTEXT, testUser.getSignIn());
        assertEquals("Email is for test2 user", testUser.getEmail(), newSession.getParticipant().getEmail());
        assertEquals("Should update the existing session instead of creating a new one.",
                sessionToken, newSession.getSessionToken());
    }

    @Test
    public void signInSetsSharingScope() {
        initTestUser();
        UserSession newSession = authService.signIn(testUser.getStudy(), TEST_CONTEXT, testUser.getSignIn());
        assertEquals(SharingScope.NO_SHARING, newSession.getParticipant().getSharingScope()); // this is the default.
    }

    @Test
    public void getSessionWithBogusSessionToken() throws Exception {
        UserSession session = authService.getSession("anytoken");
        assertNull("Session is null", session);

        session = authService.getSession(null);
        assertNull("Session is null", session);
    }

    @Test
    public void getSessionWhenAuthenticated() throws Exception {
        initTestUser();
        UserSession newSession = authService.getSession(testUser.getSessionToken());

        assertEquals("Email is for test2 user", testUser.getEmail(), newSession.getParticipant().getEmail());
        assertTrue("Session token has been assigned", StringUtils.isNotBlank(newSession.getSessionToken()));
    }

    @Test(expected = NullPointerException.class)
    public void requestPasswordResetFailsOnNull() throws Exception {
        authService.requestResetPassword(study, null);
    }

    @Test(expected = InvalidEntityException.class)
    public void requestPasswordResetFailsOnEmptyString() throws Exception {
        Email email = new Email(TEST_STUDY_IDENTIFIER, "");
        authService.requestResetPassword(study, email);
    }
    
    @Test(expected = BadRequestException.class)
    public void resetPasswordWithBadTokenFails() throws Exception {
        authService.resetPassword(new PasswordReset("newpassword", "resettoken", "api"));
    }

    @Test
    public void canResendEmailVerification() throws Exception {
        testUser = helper.getBuilder(AuthenticationServiceTest.class).withConsent(false).withSignIn(false).build();
        Email email = new Email(testUser.getStudyIdentifier(), testUser.getEmail());
        authService.resendEmailVerification(testUser.getStudyIdentifier(), email);
    }

    @Test
    public void createResearcherAndSignInWithoutConsentError() {
        testUser = helper.getBuilder(AuthenticationServiceTest.class)
                .withConsent(false).withSignIn(false).withRoles(Roles.RESEARCHER).build();
        // Can no longer delete an account without getting a session, and the assigned ID, first, so there's
        // no way to use finally here if sign in fails for some reason.
        UserSession session = authService.signIn(testUser.getStudy(), TEST_CONTEXT, testUser.getSignIn());
        helper.deleteUser(testUser.getStudy(), session.getId());
    }

    @Test
    public void createAdminAndSignInWithoutConsentError() {
        TestUser testUser = helper.getBuilder(AuthenticationServiceTest.class)
                .withConsent(false).withSignIn(false).withRoles(Roles.ADMIN).build();
        UserSession session = authService.signIn(testUser.getStudy(), TEST_CONTEXT, testUser.getSignIn());
        helper.deleteUser(testUser.getStudy(), session.getId());
    }

    @Test
    public void testSignOut() {
        initTestUser();
        final String sessionToken = testUser.getSessionToken();
        final String userId = testUser.getId();
        authService.signOut(testUser.getSession());
        assertNull(cacheProvider.getUserSession(sessionToken));
        assertNull(cacheProvider.getUserSessionByUserId(userId));
    }

    @Test
    public void testSignOutWhenSignedOut() {
        initTestUser();
        final String sessionToken = testUser.getSessionToken();
        final String userId = testUser.getId();
        authService.signOut(testUser.getSession());
        authService.signOut(testUser.getSession());
        assertNull(cacheProvider.getUserSession(sessionToken));
        assertNull(cacheProvider.getUserSessionByUserId(userId));
    }
    
    // This test combines test of dataGroups, languages, and other data that can be set.
    @Test
    public void signUpDataExistsOnSignIn() {
        StudyParticipant participant = TestUtils.getStudyParticipant(AuthenticationServiceTest.class);
        IdentifierHolder holder = null;
        try {
            holder = authService.signUp(study, participant);
            
            StudyParticipant persisted = participantService.getParticipant(study, holder.getIdentifier(), false);
            assertEquals(participant.getFirstName(), persisted.getFirstName());
            assertEquals(participant.getLastName(), persisted.getLastName());
            assertEquals(participant.getEmail(), persisted.getEmail());
            assertEquals(participant.getExternalId(), persisted.getExternalId());
            assertEquals(participant.getSharingScope(), persisted.getSharingScope());
            assertTrue(persisted.isNotifyByEmail());
            assertNotNull(persisted.getId());
            assertEquals(participant.getDataGroups(), persisted.getDataGroups());
            assertEquals(participant.getAttributes().get("phone"), persisted.getAttributes().get("phone"));
            assertEquals(participant.getLanguages(), persisted.getLanguages());
        } finally {
            if (holder != null) {
                userAdminService.deleteUser(study, holder.getIdentifier());    
            }
        }
    }
    
    @Test
    public void signUpWillCreateDataGroups() {
        String name = TestUtils.randomName(AuthenticationServiceTest.class);
        String email = "bridge-testing+"+name+"@sagebase.org";
        Set<String> groups = Sets.newHashSet("group1");
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withEmail(email).withPassword("P@ssword1").withDataGroups(groups).build();

        IdentifierHolder holder = authService.signUp(study, participant);
        
        Account account = accountDao.getAccount(study, holder.getIdentifier());
        
        Set<String> persistedGroups = optionsService.getOptions(account.getHealthCode()).getStringSet(DATA_GROUPS);
        assertEquals(groups, persistedGroups);
    }
    
    @Test
    public void userCreatedWithDataGroupsHasThemOnSignIn() throws Exception {
        int numOfGroups = DefaultStudyBootstrapper.TEST_DATA_GROUPS.size();
        testUser = helper.getBuilder(AuthenticationServiceTest.class).withConsent(true)
                .withDataGroups(DefaultStudyBootstrapper.TEST_DATA_GROUPS).build();

        UserSession session = authService.signIn(testUser.getStudy(), TEST_CONTEXT, testUser.getSignIn());
        // Verify we created a list and the anticipated group was not null
        assertEquals(numOfGroups, session.getParticipant().getDataGroups().size()); 
        assertEquals(DefaultStudyBootstrapper.TEST_DATA_GROUPS, session.getParticipant().getDataGroups());
    }
    
    @Test(expected = InvalidEntityException.class)
    public void invalidDataGroupsAreRejected() throws Exception {
        Set<String> dataGroups = Sets.newHashSet("bugleboy");
        helper.getBuilder(AuthenticationServiceTest.class).withConsent(false).withSignIn(false)
                .withDataGroups(dataGroups).build();
    }
    
    // Account enumeration security. Verify the service is quite (throws no exceptions) when we don't
    // recognize an account.

    @Test
    public void secondSignUpTriggersResetPasswordInstead() {
        // First sign up
        testUser = helper.getBuilder(AuthenticationServiceTest.class)
                .withConsent(false).withSignIn(false).build();
        
        AccountWorkflowService accountWorkflowServiceSpy = spy(accountWorkflowService);
        authService.setAccountWorkflowService(accountWorkflowServiceSpy);

        // Second sign up
        authService.signUp(testUser.getStudy(), testUser.getStudyParticipant());
        
        ArgumentCaptor<Email> emailCaptor = ArgumentCaptor.forClass(Email.class);
        verify(accountWorkflowServiceSpy).notifyAccountExists(eq(testUser.getStudy()), emailCaptor.capture());
        assertEquals(testUser.getStudyIdentifier(), emailCaptor.getValue().getStudyIdentifier());
        assertEquals(testUser.getEmail(), emailCaptor.getValue().getEmail());
    }
    
    @Test
    public void resendEmailVerificationLooksSuccessfulWhenNoAccount() throws Exception {
        Email email = new Email(TEST_STUDY_IDENTIFIER, "notarealaccount@sagebase.org");
        authService.resendEmailVerification(study, email);
    }
    
    @Test
    public void requestResetPasswordLooksSuccessfulWhenNoAccount() throws Exception {
        Email email = new Email(TEST_STUDY_IDENTIFIER, "notarealaccount@sagebase.org");
        authService.requestResetPassword(study, email);
    }
    
    // Consent statuses passed on to sessionInfo
    
    @Test
    public void consentStatusesPresentInSession() throws Exception {
        // User is consenting
        testUser = helper.getBuilder(AuthenticationServiceTest.class)
                .withConsent(true).withSignIn(true).build();

        JsonNode info = UserSessionInfo.toJSON(testUser.getSession());
        
        TypeReference<Map<SubpopulationGuid,ConsentStatus>> tRef = new TypeReference<Map<SubpopulationGuid,ConsentStatus>>() {};
        Map<SubpopulationGuid,ConsentStatus> statuses = BridgeObjectMapper.get().readValue(info.get("consentStatuses").toString(), tRef); 
        
        ConsentStatus status = statuses.get(SubpopulationGuid.create(testUser.getStudyIdentifier().getIdentifier()));
        assertTrue(status.isConsented());
        assertEquals(testUser.getStudyIdentifier().getIdentifier(), status.getSubpopulationGuid());
    }
    
    @Test
    public void existingLanguagePreferencesAreLoaded() {
        LinkedHashSet<String> LANGS = TestUtils.newLinkedHashSet("en","es");
        testUser = helper.getBuilder(AuthenticationServiceTest.class)
                .withConsent(true).withSignIn(true).build();
        
        String healthCode = testUser.getHealthCode();
        optionsService.setOrderedStringSet(
                testUser.getStudyIdentifier(), healthCode, ParticipantOption.LANGUAGES, LANGS);

        authService.signOut(testUser.getSession());
        
        Study study = studyService.getStudy(testUser.getStudyIdentifier());
        CriteriaContext context = testUser.getCriteriaContext();
        
        UserSession session = authService.signIn(study, context, testUser.getSignIn());
        assertEquals(LANGS, session.getParticipant().getLanguages());
    }
    
    @Test
    public void languagePreferencesAreRetrievedFromContext() {
        LinkedHashSet<String> LANGS = TestUtils.newLinkedHashSet("fr","es");
        testUser = helper.getBuilder(AuthenticationServiceTest.class)
                .withConsent(true).withSignIn(true).build();
        
        StudyParticipant participant = new StudyParticipant.Builder().copyOf(testUser.getStudyParticipant())
                .withLanguages(LANGS).build();
        
        testUser.getSession().setParticipant(participant);
        CriteriaContext context = testUser.getCriteriaContext();
        
        authService.signOut(testUser.getSession());
        
        Study study = studyService.getStudy(testUser.getStudyIdentifier());
        
        UserSession session = authService.signIn(study, context, testUser.getSignIn());
        assertEquals(LANGS, session.getParticipant().getLanguages());
        
        LinkedHashSet<String> persistedLangs = optionsService.getOptions(testUser.getHealthCode()).getOrderedStringSet(LANGUAGES);
        assertEquals(LANGS, persistedLangs);
    }
    
    @Test
    public void updateSession() {
        testUser = helper.getBuilder(AuthenticationServiceTest.class).withConsent(false)
                .withDataGroups(ORIGINAL_DATA_GROUPS).withSignIn(false).build();
        String userId = testUser.getId();
        
        // Update the data groups
        StudyParticipant participant = participantService.getParticipant(study, userId, false);
        StudyParticipant updated = new StudyParticipant.Builder().copyOf(participant)
                .withDataGroups(UPDATED_DATA_GROUPS).withId(userId).build();
        participantService.updateParticipant(study, CALLER_ROLES, updated);
        
        // Now update the session, these changes should be reflected
        CriteriaContext context = new CriteriaContext.Builder().withStudyIdentifier(study.getStudyIdentifier())
                .withUserId(userId).build();
        Set<String> retrievedSessionDataGroups = authService.getSession(study, context)
                .getParticipant().getDataGroups();

        assertEquals(UPDATED_DATA_GROUPS, retrievedSessionDataGroups);
    }
    
    @Test
    public void signUpWillNotSetRoles() {
        String email = TestUtils.makeRandomTestEmail(AuthenticationServiceTest.class);
        Set<Roles> roles = Sets.newHashSet(Roles.DEVELOPER, Roles.RESEARCHER);
        StudyParticipant participant = new StudyParticipant.Builder()
                .withEmail(email).withPassword("P@ssword`1").withRoles(roles).build();
        
        IdentifierHolder idHolder = authService.signUp(study, participant);
        
        participant = participantService.getParticipant(study, idHolder.getIdentifier(), false);
        assertTrue(participant.getRoles().isEmpty());
    }
    
    @Test
    public void signInRefreshesSessionKeepingTokens() {
        testUser = helper.getBuilder(AuthenticationServiceTest.class).withConsent(false).withSignIn(false).build();
        
        // User's ID ties this record to the newly signed in user, which contains only an ID. So the rest of the 
        // session should be initialized from scratch.
        StudyParticipant oldRecord = new StudyParticipant.Builder()
                .withHealthCode("oldHealthCode")
                .withId(testUser.getId()).build();
        UserSession cachedSession = new UserSession(oldRecord);
        cachedSession.setSessionToken("cachedSessionToken");
        cachedSession.setInternalSessionToken("cachedInternalSessionToken");
        cacheProvider.setUserSession(cachedSession);
        
        UserSession session = authService.signIn(testUser.getStudy(), TEST_CONTEXT, testUser.getSignIn());
        
        assertEquals(cachedSession.getSessionToken(), session.getSessionToken());
        assertEquals(cachedSession.getInternalSessionToken(), session.getInternalSessionToken());
        // but the rest is updated.  
        assertEquals(testUser.getStudyParticipant().getEmail(), session.getParticipant().getEmail());
        assertEquals(testUser.getStudyParticipant().getFirstName(), session.getParticipant().getFirstName());
        assertEquals(testUser.getStudyParticipant().getLastName(), session.getParticipant().getLastName());
        assertEquals(testUser.getStudyParticipant().getHealthCode(), session.getHealthCode());
        // etc.
    }
}
