package org.sagebionetworks.bridge.play.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.bridge.Roles.ADMIN;
import static org.sagebionetworks.bridge.Roles.DEVELOPER;
import static org.sagebionetworks.bridge.Roles.RESEARCHER;
import static org.sagebionetworks.bridge.Roles.WORKER;
import static org.sagebionetworks.bridge.TestConstants.TEST_STUDY;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.sagebionetworks.bridge.BridgeConstants;
import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.Roles;
import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.cache.CacheKey;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.cache.ViewCache;
import org.sagebionetworks.bridge.dynamodb.DynamoSurvey;
import org.sagebionetworks.bridge.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.models.GuidCreatedOnVersionHolderImpl;
import org.sagebionetworks.bridge.models.ResourceList;
import org.sagebionetworks.bridge.models.accounts.ConsentStatus;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.studies.StudyIdentifierImpl;
import org.sagebionetworks.bridge.models.subpopulations.SubpopulationGuid;
import org.sagebionetworks.bridge.models.surveys.Survey;
import org.sagebionetworks.bridge.models.surveys.TestSurvey;
import org.sagebionetworks.bridge.services.StudyService;
import org.sagebionetworks.bridge.services.SurveyService;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import play.mvc.Result;
import play.test.Helpers;

/**
 * We know this controller works given the integration tests. Here I'm interested in finding a way 
 * to test cross-study security that won't take another hour in test time to run. Creating a second study, 
 * etc. through integration tests is very slow.
 */
public class SurveyControllerTest {

    private static final TypeReference<ResourceList<Survey>> TYPE_REF_SURVEY_LIST =
            new TypeReference<ResourceList<Survey>>(){};

    private static final boolean CONSENTED = true;
    private static final boolean UNCONSENTED = false;
    private static final StudyIdentifier API_STUDY_ID = TestConstants.TEST_STUDY;
    private static final StudyIdentifier SECONDSTUDY_STUDY_ID = new StudyIdentifierImpl("secondstudy");
    private static final String SURVEY_GUID = "bbb";
    private static final DateTime CREATED_ON = DateTime.now();
    private static final GuidCreatedOnVersionHolder KEYS = new GuidCreatedOnVersionHolderImpl(SURVEY_GUID, CREATED_ON.getMillis());
    
    private SurveyController controller;
    
    private SurveyService service;
    
    private StudyService studyService;
    
    private ViewCache viewCache;
    
    private Map<CacheKey,String> cacheMap;
    
    private UserSession session;
    
    @Before
    public void before() {
        // Finish mocking this in each test?
        service = mock(SurveyService.class);
        
        // Dummy this out so it works and we can forget about it as a dependency
        cacheMap = Maps.newHashMap();
        viewCache = new ViewCache();
        viewCache.setObjectMapper(BridgeObjectMapper.get());
        viewCache.setCachePeriod(BridgeConstants.BRIDGE_VIEW_EXPIRE_IN_SECONDS);
        
        CacheProvider provider = mock(CacheProvider.class);
        when(provider.getObject(any(), eq(String.class))).thenAnswer(new Answer<String>() {
            @Override
            public String answer(InvocationOnMock invocation) throws Throwable {
                CacheKey key = invocation.getArgument(0);
                return cacheMap.get(key);
            }
        });
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                CacheKey key = invocation.getArgument(0);
                String value = invocation.getArgument(1);
                cacheMap.put(key, value);
                return null;
            }
        }).when(provider).setObject(any(), anyString(), anyInt());
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                CacheKey key = invocation.getArgument(0);
                cacheMap.remove(key);
                return null;
            }
        }).when(provider).removeObject(any());
        viewCache.setCacheProvider(provider);
        
        studyService = mock(StudyService.class);
        Study study = Study.create();
        doReturn(study).when(studyService).getStudy(any(StudyIdentifier.class));
        
        controller = spy(new SurveyController());
        controller.setSurveyService(service);
        controller.setViewCache(viewCache);
        controller.setStudyService(studyService);
    }
    
    private void setupContext(StudyIdentifier studyIdentifier, Roles role, boolean hasConsented) throws Exception {
        // Create a participant (with a role, if given)
        StudyParticipant.Builder builder = new StudyParticipant.Builder().withHealthCode("BBB");
        if (role != null) {
            builder.withRoles(Sets.newHashSet(role)).build();
        }
        StudyParticipant participant = builder.build();

        // Set up a session that is returned as if the user is already signed in.
        session = new UserSession(participant);
        session.setStudyIdentifier(studyIdentifier);
        session.setAuthenticated(true);
        doReturn(session).when(controller).getSessionIfItExists();
        
        // ... and setup session to report user consented, if needed.
        if (hasConsented) {
            Map<SubpopulationGuid, ConsentStatus> consentStatuses = TestUtils
                    .toMap(new ConsentStatus.Builder().withName("Name").withConsented(true)
                            .withGuid(SubpopulationGuid.create("guid")).withSignedMostRecentConsent(true).build());
            session.setConsentStatuses(consentStatuses);
        }
    }
    
    @Test
    public void verifyViewCacheIsWorking() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, CONSENTED);
        TestUtils.mockPlay().withMockResponse().mock();
        when(service.getSurveyMostRecentlyPublishedVersion(any(StudyIdentifier.class), anyString(), eq(true))).thenReturn(getSurvey(false));
        
        controller.getSurveyMostRecentlyPublishedVersionForUser(SURVEY_GUID);
        controller.getSurveyMostRecentlyPublishedVersionForUser(SURVEY_GUID);
        
        verify(service, times(1)).getSurveyMostRecentlyPublishedVersion(API_STUDY_ID, SURVEY_GUID, true);
        verify(controller, times(2)).getAuthenticatedAndConsentedSession();
        verifyNoMoreInteractions(service);
    }

    @Test
    public void getAllSurveysMostRecentVersionDoNotIncludeDeletedAsDefault() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        when(service.getAllSurveysMostRecentVersion(API_STUDY_ID, false)).thenReturn(getSurveys(3, false));
        
        controller.getAllSurveysMostRecentVersion(null);
        
        verify(service).getAllSurveysMostRecentVersion(API_STUDY_ID, false);
        verify(controller).getAuthenticatedSession(DEVELOPER, RESEARCHER);
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void getAllSurveysMostRecentVersionDoNotIncludeDeleted() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().withBody("false").mock();
        when(service.getAllSurveysMostRecentVersion(API_STUDY_ID, false)).thenReturn(getSurveys(3, false));
        
        controller.getAllSurveysMostRecentVersion("false");
        
        verify(service).getAllSurveysMostRecentVersion(API_STUDY_ID, false);
        verify(controller).getAuthenticatedSession(DEVELOPER, RESEARCHER);
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void getAllSurveysMostRecentVersionIncludeDeleted() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        when(service.getAllSurveysMostRecentVersion(API_STUDY_ID, true)).thenReturn(getSurveys(3, false));
        
        controller.getAllSurveysMostRecentVersion("true");
        
        verify(service).getAllSurveysMostRecentVersion(API_STUDY_ID, true);
        verify(controller).getAuthenticatedSession(DEVELOPER, RESEARCHER);
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void getAllSurveysMostRecentlyPublishedVersionDoNotIncludeDeletedDefault() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        when(service.getAllSurveysMostRecentlyPublishedVersion(API_STUDY_ID, false)).thenReturn(getSurveys(2, false));
        
        controller.getAllSurveysMostRecentlyPublishedVersion(null);
        
        verify(service).getAllSurveysMostRecentlyPublishedVersion(API_STUDY_ID, false);
        verify(controller).getAuthenticatedSession(DEVELOPER);
        verifyNoMoreInteractions(service);
    }

    @Test
    public void getAllSurveysMostRecentlyPublishedVersionDoNotIncludeDeleted() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        when(service.getAllSurveysMostRecentlyPublishedVersion(API_STUDY_ID, false)).thenReturn(getSurveys(2, false));
        
        controller.getAllSurveysMostRecentlyPublishedVersion("false");
        
        verify(service).getAllSurveysMostRecentlyPublishedVersion(API_STUDY_ID, false);
        verify(controller).getAuthenticatedSession(DEVELOPER);
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void getAllSurveysMostRecentlyPublishedVersionIncludeDeleted() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        when(service.getAllSurveysMostRecentlyPublishedVersion(API_STUDY_ID, true)).thenReturn(getSurveys(2, false));
        
        controller.getAllSurveysMostRecentlyPublishedVersion("true");
        
        verify(service).getAllSurveysMostRecentlyPublishedVersion(API_STUDY_ID, true);
        verify(controller).getAuthenticatedSession(DEVELOPER);
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void getAllSurveysMostRecentlyPublishedVersionForStudy() throws Exception {
        setupContext(SECONDSTUDY_STUDY_ID, WORKER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        // make surveys
        List<Survey> surveyList = getSurveys(2, false);
        surveyList.get(0).setGuid("survey-0");
        surveyList.get(1).setGuid("survey-1");
        when(service.getAllSurveysMostRecentlyPublishedVersion(API_STUDY_ID, false)).thenReturn(surveyList);

        // execute and validate
        Result result = controller.getAllSurveysMostRecentlyPublishedVersionForStudy(API_STUDY_ID.getIdentifier(), "false");
        TestUtils.assertResult(result, 200);
        
        String resultStr = Helpers.contentAsString(result);
        ResourceList<Survey> resultSurveyResourceList = BridgeObjectMapper.get().readValue(resultStr,
                TYPE_REF_SURVEY_LIST);
        List<Survey> resultSurveyList = resultSurveyResourceList.getItems();
        assertEquals(2, resultSurveyList.size());
        assertEquals("survey-0", resultSurveyList.get(0).getGuid());
        assertEquals("survey-1", resultSurveyList.get(1).getGuid());
        
        verify(controller).getAuthenticatedSession(Roles.WORKER);
    }

    @Test
    public void getSurveyForUser() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().withMockResponse().mock();
        when(service.getSurvey(TestConstants.TEST_STUDY, KEYS, true, true)).thenReturn(getSurvey(false));
        
        controller.getSurvey(SURVEY_GUID, CREATED_ON.toString());
        
        verify(service).getSurvey(TestConstants.TEST_STUDY, KEYS, true, true);
        verify(controller).getSessionEitherConsentedOrInRole(WORKER, DEVELOPER);
        verifyNoMoreInteractions(service);
    }

    @Test
    public void getSurveyMostRecentlyPublishedVersionForUser() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, CONSENTED);
        TestUtils.mockPlay().withMockResponse().mock();
        when(service.getSurveyMostRecentlyPublishedVersion(API_STUDY_ID, SURVEY_GUID, true)).thenReturn(getSurvey(false));
        
        controller.getSurveyMostRecentlyPublishedVersionForUser(SURVEY_GUID);
        
        verify(service).getSurveyMostRecentlyPublishedVersion(API_STUDY_ID, SURVEY_GUID, true);
        verify(controller).getAuthenticatedAndConsentedSession();
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void getSurvey() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, CONSENTED);
        TestUtils.mockPlay().withMockResponse().mock();
        when(service.getSurvey(TestConstants.TEST_STUDY, KEYS, true, true)).thenReturn(getSurvey(false));
        
        controller.getSurvey(SURVEY_GUID, CREATED_ON.toString());
        
        verify(service).getSurvey(TestConstants.TEST_STUDY, KEYS, true, true);
        verify(controller).getSessionEitherConsentedOrInRole(WORKER, DEVELOPER);
        verifyNoMoreInteractions(service);
    }

    @Test
    public void getSurveyForWorker() throws Exception {
        setupContext(TestConstants.TEST_STUDY, WORKER, UNCONSENTED);
        TestUtils.mockPlay().withMockResponse().mock();
        // make survey
        Survey survey = getSurvey(false);
        survey.setGuid("test-survey");
        when(service.getSurvey(null, KEYS, true, true)).thenReturn(survey);

        // execute and validate
        Result result = controller.getSurvey(SURVEY_GUID, CREATED_ON.toString());
        TestUtils.assertResult(result, 200);
        
        String resultStr = Helpers.contentAsString(result);
        Survey resultSurvey = BridgeObjectMapper.get().readValue(resultStr, Survey.class);
        assertEquals("test-survey", resultSurvey.getGuid());
        
        verify(controller).getSessionEitherConsentedOrInRole(WORKER, DEVELOPER);
    }

    @Test
    public void getSurveyMostRecentVersion() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        when(service.getSurveyMostRecentVersion(API_STUDY_ID, SURVEY_GUID)).thenReturn(getSurvey(false));
        
        Result result = controller.getSurveyMostRecentVersion(SURVEY_GUID);
        TestUtils.assertResult(result, 200);

        verify(service).getSurveyMostRecentVersion(API_STUDY_ID, SURVEY_GUID);
        verify(controller).getAuthenticatedSession(DEVELOPER);
        verifyNoMoreInteractions(service);
    }

    @Test
    public void getSurveyMostRecentlyPublishedVersion() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().withMockResponse().mock();
        when(service.getSurveyMostRecentlyPublishedVersion(API_STUDY_ID, SURVEY_GUID, true)).thenReturn(getSurvey(false));
        
        Result result = controller.getSurveyMostRecentlyPublishedVersion(SURVEY_GUID);
        TestUtils.assertResult(result, 200);

        verify(service).getSurveyMostRecentlyPublishedVersion(API_STUDY_ID, SURVEY_GUID, true);
        verify(controller).getSessionEitherConsentedOrInRole(DEVELOPER);
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void deleteSurveyDefaultsToLogicalDelete() throws Exception {
        setupContext(API_STUDY_ID, ADMIN, UNCONSENTED);
        TestUtils.mockPlay().mock();
        Survey survey = getSurvey(false);
        when(service.getSurvey(TestConstants.TEST_STUDY, KEYS, false, false)).thenReturn(survey);
        
        Result result = controller.deleteSurvey(SURVEY_GUID, CREATED_ON.toString(), "nonsense");
        TestUtils.assertResult(result, 200, "Survey deleted.");
        
        verify(service).getSurvey(TestConstants.TEST_STUDY, KEYS, false, false);
        verify(service).deleteSurvey(TestConstants.TEST_STUDY, survey);
        verify(controller).getAuthenticatedSession(DEVELOPER, ADMIN);
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void developerCanLogicallyDelete() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        Survey survey = getSurvey(false);
        when(service.getSurvey(TestConstants.TEST_STUDY, KEYS, false, false)).thenReturn(survey);
        
        Result result = controller.deleteSurvey(SURVEY_GUID, CREATED_ON.toString(), "false");
        TestUtils.assertResult(result, 200, "Survey deleted.");
        
        verify(service).getSurvey(TestConstants.TEST_STUDY, KEYS, false, false);
        verify(service).deleteSurvey(TestConstants.TEST_STUDY, survey);
        verify(controller).getAuthenticatedSession(DEVELOPER, ADMIN);
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void adminCanLogicallyDelete() throws Exception {
        setupContext(API_STUDY_ID, ADMIN, UNCONSENTED);
        TestUtils.mockPlay().mock();
        Survey survey = getSurvey(false);
        when(service.getSurvey(TestConstants.TEST_STUDY, KEYS, false, false)).thenReturn(survey);
        
        Result result = controller.deleteSurvey(SURVEY_GUID, CREATED_ON.toString(), "false");
        TestUtils.assertResult(result, 200, "Survey deleted.");
        
        verify(service).getSurvey(TestConstants.TEST_STUDY, KEYS, false, false);
        verify(service).deleteSurvey(TestConstants.TEST_STUDY, survey);
        verify(controller).getAuthenticatedSession(DEVELOPER, ADMIN);
        verifyNoMoreInteractions(service);
    }
    
    @Test(expected = UnauthorizedException.class)
    public void workerCannotDelete() throws Exception {
        setupContext(API_STUDY_ID, WORKER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        
        controller.deleteSurvey(SURVEY_GUID, CREATED_ON.toString(), "false");
        
        verify(controller).getAuthenticatedSession(DEVELOPER, ADMIN);
    }
    
    @Test
    public void deleteSurveyAllowedForDeveloper() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        Survey survey = getSurvey(false);
        when(service.getSurvey(TestConstants.TEST_STUDY, KEYS, false, false))
                .thenReturn(survey);
        
        Result result = controller.deleteSurvey(SURVEY_GUID, CREATED_ON.toString(), "false");
        TestUtils.assertResult(result, 200, "Survey deleted.");

        verify(service).getSurvey(TestConstants.TEST_STUDY, KEYS, false, false);
        verify(service).deleteSurvey(TestConstants.TEST_STUDY, survey);
        verify(controller).getAuthenticatedSession(DEVELOPER, ADMIN);
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void physicalDeleteOfSurveyNotAllowedForDeveloper() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        Survey survey = getSurvey(false);
        when(service.getSurvey(TestConstants.TEST_STUDY, KEYS, false, false)).thenReturn(survey);
        
        Result result = controller.deleteSurvey(SURVEY_GUID, CREATED_ON.toString(), "true");
        TestUtils.assertResult(result, 200, "Survey deleted.");
        
        verify(service).getSurvey(TestConstants.TEST_STUDY, KEYS, false, false);
        verify(service).deleteSurvey(TestConstants.TEST_STUDY, survey);
        verify(controller).getAuthenticatedSession(DEVELOPER, ADMIN);
        verifyNoMoreInteractions(service);
    }
    
    public void physicalDeleteAllowedForAdmin() throws Exception {
        setupContext(API_STUDY_ID, ADMIN, UNCONSENTED);
        TestUtils.mockPlay().mock();
        Survey survey = getSurvey(false);
        when(service.getSurvey(TestConstants.TEST_STUDY, KEYS, true, false)).thenReturn(survey);
        
        Result result = controller.deleteSurvey(SURVEY_GUID, CREATED_ON.toString(), "true");
        TestUtils.assertResult(result, 200, "Survey deleted.");
        
        verify(service).getSurvey(TestConstants.TEST_STUDY, KEYS, true, true);
        verify(service).deleteSurveyPermanently(API_STUDY_ID, survey);
        verify(controller).getAuthenticatedSession(DEVELOPER, ADMIN);
        verifyNoMoreInteractions(service);
    }
    
    @Test(expected = EntityNotFoundException.class)
    public void deleteSurveyThrowsGoodExceptionIfSurveyDoesntExist() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        when(service.getSurvey(TestConstants.TEST_STUDY, KEYS, false, false)).thenReturn(null);
        
        controller.deleteSurvey(SURVEY_GUID, CREATED_ON.toString(), "false");
        
        verify(controller).getAuthenticatedSession(DEVELOPER, ADMIN);
    }
    
    @Test
    public void getSurveyAllVersionsExcludeDeletedByDefault() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        when(service.getSurveyAllVersions(API_STUDY_ID, SURVEY_GUID, false)).thenReturn(getSurveys(3, false));
        
        Result result = controller.getSurveyAllVersions(SURVEY_GUID, null);
        TestUtils.assertResult(result, 200);
        
        verify(service).getSurveyAllVersions(API_STUDY_ID, SURVEY_GUID, false);
        verify(controller).getAuthenticatedSession(DEVELOPER);
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void getSurveyAllVersionsExcludeDeleted() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        when(service.getSurveyAllVersions(API_STUDY_ID, SURVEY_GUID, false)).thenReturn(getSurveys(3, false));
        
        Result result = controller.getSurveyAllVersions(SURVEY_GUID, "false");
        TestUtils.assertResult(result, 200);
        
        verify(service).getSurveyAllVersions(API_STUDY_ID, SURVEY_GUID, false);
        verify(controller).getAuthenticatedSession(DEVELOPER);
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void getSurveyAllVersionsIncludeDeleted() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        when(service.getSurveyAllVersions(API_STUDY_ID, SURVEY_GUID, true)).thenReturn(getSurveys(3, false));
        
        Result result = controller.getSurveyAllVersions(SURVEY_GUID, "true");
        TestUtils.assertResult(result, 200);
        
        verify(service).getSurveyAllVersions(API_STUDY_ID, SURVEY_GUID, true);
        verify(controller).getAuthenticatedSession(DEVELOPER);
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void createSurvey() throws Exception {
        Survey survey = getSurvey(true);
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().withBody(survey).mock();
        survey.setGuid(BridgeUtils.generateGuid());
        survey.setVersion(1L);
        survey.setCreatedOn(DateTime.now().getMillis());
        when(service.createSurvey(any(Survey.class))).thenReturn(survey);
        
        Result result = controller.createSurvey();
        TestUtils.assertResult(result, 201);

        verify(service).createSurvey(any(Survey.class));
        verify(controller).getAuthenticatedSession(DEVELOPER);
        verifyNoMoreInteractions(service);
    }
    
    // There's no such thing as not being able to create a study from another study. If
    // you create a survey, it's in your study.
    
    @Test
    public void versionSurvey() throws Exception {
        Survey survey = getSurvey(false);
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().withBody(survey).mock();
        when(service.versionSurvey(eq(TestConstants.TEST_STUDY), any(GuidCreatedOnVersionHolder.class))).thenReturn(survey);
        
        Result result = controller.versionSurvey(SURVEY_GUID, CREATED_ON.toString());
        TestUtils.assertResult(result, 201);
        
        GuidCreatedOnVersionHolder keys = new GuidCreatedOnVersionHolderImpl(SURVEY_GUID, CREATED_ON.getMillis());
        
        verify(service).versionSurvey(TestConstants.TEST_STUDY, keys);
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void updateSurvey() throws Exception {
        Survey survey = getSurvey(false);
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().withBody(survey).mock();
        when(service.updateSurvey(eq(TestConstants.TEST_STUDY), any(Survey.class))).thenReturn(survey);
        
        Result result = controller.updateSurvey(SURVEY_GUID, CREATED_ON.toString());
        TestUtils.assertResult(result, 200);
        
        verify(service).updateSurvey(eq(TestConstants.TEST_STUDY), any(Survey.class));
        verify(controller).getAuthenticatedSession(DEVELOPER);
        verifyNoMoreInteractions(service);
    }
    
    @Test
    public void publishSurvey() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        Survey survey = getSurvey(false);
        when(service.publishSurvey(eq(TestConstants.TEST_STUDY), eq(KEYS), eq(false))).thenReturn(survey);

        Result result = controller.publishSurvey(SURVEY_GUID, CREATED_ON.toString(), null);
        TestUtils.assertResult(result, 200);
        
        verify(service).publishSurvey(TEST_STUDY, KEYS, false);
        verify(controller).getAuthenticatedSession(DEVELOPER);
        verifyNoMoreInteractions(service);
    }

    @Test
    public void publishSurveyNewSchemaRev() throws Exception {
        setupContext(API_STUDY_ID, DEVELOPER, UNCONSENTED);
        TestUtils.mockPlay().mock();
        Survey survey = getSurvey(false);
        when(service.publishSurvey(eq(TestConstants.TEST_STUDY), eq(KEYS), eq(true))).thenReturn(survey);

        Result result = controller.publishSurvey(SURVEY_GUID, CREATED_ON.toString(), "true");
        TestUtils.assertResult(result, 200);
        
        verify(service).publishSurvey(TEST_STUDY, KEYS, true);
        verify(controller).getAuthenticatedSession(DEVELOPER);
        verifyNoMoreInteractions(service);
    }

    @Test
    public void adminRejectedAsUnauthorized() throws Exception {
        setupContext(API_STUDY_ID, ADMIN, UNCONSENTED);
        TestUtils.mockPlay().withMockResponse().mock();
        Survey survey = getSurvey(false);
        when(service.getSurvey(TestConstants.TEST_STUDY, KEYS, true, true)).thenReturn(survey);
        
        try {
            controller.getSurvey(SURVEY_GUID, CREATED_ON.toString());
            fail("Exception should have been thrown.");
        } catch(UnauthorizedException e) {
            verifyNoMoreInteractions(service);
        }
    }
    
    @Test
    public void studyParticipantRejectedAsNotConsented() throws Exception {
        setupContext(API_STUDY_ID, null, UNCONSENTED);
        TestUtils.mockPlay().withMockResponse().mock();
        Survey survey = getSurvey(false);
        when(service.getSurvey(TestConstants.TEST_STUDY, KEYS, true, true)).thenReturn(survey);
        
        try {
            controller.getSurvey(SURVEY_GUID, CREATED_ON.toString());
            fail("Exception should have been thrown.");
        } catch(ConsentRequiredException e) {
            verifyNoMoreInteractions(service);
        }
    }    
    
    @Test
    public void deleteSurveyInvalidatesCache() throws Exception {
        assertCacheIsCleared((guid, dateString) -> controller.deleteSurvey(guid, dateString, "false"), 2);
    }
    
    @Test
    public void versionSurveyInvalidatesCache() throws Exception {
        assertCacheIsCleared((guid, dateString) -> controller.versionSurvey(guid, dateString));
    }
    
    @Test
    public void updateSurveyInvalidatesCache() throws Exception {
        assertCacheIsCleared((guid, dateString) -> controller.updateSurvey(guid, dateString));
    }
    
    @Test
    public void publishSurveyInvalidatesCache() throws Exception {
        assertCacheIsCleared((guid, dateString) -> controller.publishSurvey(guid, dateString, null));
    }
    
    @FunctionalInterface
    public interface ExecuteSurvey {
        public void execute(String guid, String dateString) throws Exception;    
    }
   
    private void assertCacheIsCleared(ExecuteSurvey executeSurvey) throws Exception {
        assertCacheIsCleared(executeSurvey, 1);
    }
    
    private void assertCacheIsCleared(ExecuteSurvey executeSurvey, int getCount) throws Exception {
        // Setup the cache to return content and verify the cache returns content
        Survey survey = new DynamoSurvey();
        survey.setStudyIdentifier("api");
        survey.setGuid(SURVEY_GUID);
        survey.setCreatedOn(CREATED_ON.getMillis());
        
        setupContext(TEST_STUDY, DEVELOPER, false);
        TestUtils.mockPlay().withBody(survey).withMockResponse().mock();
        when(service.getSurvey(eq(TEST_STUDY), any(), anyBoolean(), anyBoolean())).thenReturn(survey);
        
        viewCache.getView(viewCache.getCacheKey(
                Survey.class, SURVEY_GUID, CREATED_ON.toString(), "api"), () -> { return survey; });
        
        // Verify this call hits the cache not the service
        Result result = controller.getSurvey(SURVEY_GUID, CREATED_ON.toString());
        TestUtils.assertResult(result, 200);        
        verifyNoMoreInteractions(service);

        // Now mock the service because the *next* call (publish/delete/etc) will require it. The 
        // calls under test do not reference the cache, they clear it.
        when(service.publishSurvey(any(), any(), anyBoolean())).thenReturn(survey);
        when(service.versionSurvey(eq(TestConstants.TEST_STUDY), any())).thenReturn(survey);
        when(service.updateSurvey(eq(TestConstants.TEST_STUDY), any())).thenReturn(survey);
        
        // execute the test method, this should delete the cache
        executeSurvey.execute(SURVEY_GUID, CREATED_ON.toString());
        
        // This call now hits the service, not the cache, for what should be one hit
        controller.getSurvey(SURVEY_GUID, CREATED_ON.toString());
        verify(service, times(getCount)).getSurvey(any(), any(), anyBoolean(), anyBoolean());
    }
    
    private Survey getSurvey(boolean makeNew) {
        Survey survey = new TestSurvey(SurveyControllerTest.class, makeNew);
        survey.setName(TestUtils.randomName(SurveyControllerTest.class));
        return survey;
    }
    
    private List<Survey> getSurveys(int count, boolean makeNew) {
        List<Survey> lists = Lists.newArrayList();
        for (int i=0; i < count; i++) {
            lists.add(getSurvey(makeNew));
        }
        return lists;
    }

}
