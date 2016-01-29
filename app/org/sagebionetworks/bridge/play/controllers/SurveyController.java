package org.sagebionetworks.bridge.play.controllers;

import static org.sagebionetworks.bridge.BridgeConstants.JSON_MIME_TYPE;
import static org.sagebionetworks.bridge.Roles.ADMIN;
import static org.sagebionetworks.bridge.Roles.DEVELOPER;
import static org.sagebionetworks.bridge.Roles.TEST_USERS;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.sagebionetworks.bridge.Roles;
import org.sagebionetworks.bridge.cache.ViewCache;
import org.sagebionetworks.bridge.cache.ViewCache.ViewCacheKey;
import org.sagebionetworks.bridge.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.json.DateUtils;
import org.sagebionetworks.bridge.models.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.models.GuidCreatedOnVersionHolderImpl;
import org.sagebionetworks.bridge.models.accounts.User;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.surveys.Survey;
import org.sagebionetworks.bridge.services.SurveyService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

import play.mvc.Result;

import com.google.common.base.Supplier;

@Controller
public class SurveyController extends BaseController {

    public static final String MOSTRECENT_KEY = "mostrecent";
    public static final String PUBLISHED_KEY = "published";

    private SurveyService surveyService;
    
    private ViewCache viewCache;

    @Autowired
    public void setSurveyService(SurveyService surveyService) {
        this.surveyService = surveyService;
    }
    @Autowired
    public void setViewCache(ViewCache viewCache) {
        this.viewCache = viewCache;
    }
    
    public Result getAllSurveysMostRecentVersion() throws Exception {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        StudyIdentifier studyId = session.getStudyIdentifier();

        List<Survey> surveys = surveyService.getAllSurveysMostRecentVersion(studyId);
        verifySurveyIsInStudy(session, surveys);
        return okResult(surveys);
    }
    
    public Result getAllSurveysMostRecentlyPublishedVersion() throws Exception {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        StudyIdentifier studyId = session.getStudyIdentifier();

        List<Survey> surveys = surveyService.getAllSurveysMostRecentlyPublishedVersion(studyId);
        verifySurveyIsInStudy(session, surveys);
        return okResult(surveys);
    }

    public Result getSurveyMostRecentlyPublishedVersionForUser(String surveyGuid) throws Exception {
        UserSession session = getAuthenticatedAndConsentedSession();
        
        return getCachedSurveyMostRecentlyPublishedInternal(surveyGuid, session);
    }
    
    public Result getSurvey(String surveyGuid, String createdOnString) throws Exception {
        // To get a survey you must either be a developer, or a consented participant in the study.
        // This is an unusual combination so we use canAccessSurvey() to verify it.
        UserSession session = getAuthenticatedSession();
        canAccessSurvey(session);
        
        return getCachedSurveyInternal(surveyGuid, createdOnString, session);
    }
    
    public Result getSurveyForUser(String surveyGuid, String createdOnString) throws Exception {
        UserSession session = getAuthenticatedAndConsentedSession();
        
        return getCachedSurveyInternal(surveyGuid, createdOnString, session);
    }
    
    public Result getSurveyMostRecentVersion(String surveyGuid) throws Exception {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        StudyIdentifier studyId = session.getStudyIdentifier();
        
        ViewCacheKey<Survey> cacheKey = viewCache.getCacheKey(Survey.class, surveyGuid, MOSTRECENT_KEY, studyId.getIdentifier());
        
        String json = getView(cacheKey, session, () -> {
            return surveyService.getSurveyMostRecentVersion(studyId, surveyGuid);
        });

        return ok(json).as(JSON_MIME_TYPE);
    }
    
    public Result getSurveyMostRecentlyPublishedVersion(String surveyGuid) throws Exception {
        // To get a survey you must either be a developer, or a consented participant in the study.
        // This is an unusual combination so we use canAccessSurvey() to verify it.
        UserSession session = getAuthenticatedSession();
        canAccessSurvey(session);
        
        return getCachedSurveyMostRecentlyPublishedInternal(surveyGuid, session);
    }
    
    /**
     * Administrators can pass the ?physical=true flag to this endpoint to physically delete a survey and all its 
     * survey elements, rather than only marking it deleted to maintain referential integrity. This should only be 
     * used as part of testing.
     * @param surveyGuid
     * @param createdOnString
     * @param physical
     * @return
     * @throws Exception
     */
    public Result deleteSurvey(String surveyGuid, String createdOnString, String physical) throws Exception {
        UserSession session = getAuthenticatedSession();
        StudyIdentifier studyId = session.getStudyIdentifier();
        
        // If not in either of these roles, don't do the work of getting the survey
        User user = session.getUser();
        if (!user.isInRole(DEVELOPER) && !user.isInRole(ADMIN)) {
            throw new UnauthorizedException();
        }
        
        Survey survey = getSurveyWithoutCacheInternal(surveyGuid, createdOnString, session);
        
        if ("true".equals(physical) && user.isInRole(ADMIN)) {
            surveyService.deleteSurveyPermanently(survey);
        } else if (user.isInRole(DEVELOPER)) {
            surveyService.deleteSurvey(survey);    
        } else {
            // An admin calling for a logical delete. That wasn't allowed before so we don't allow it now.
            throw new UnauthorizedException();
        }
        expireCache(surveyGuid, createdOnString, studyId);
        return okResult("Survey deleted.");
    }
    
    public Result getSurveyAllVersions(String surveyGuid) throws Exception {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        StudyIdentifier studyId = session.getStudyIdentifier();
        
        List<Survey> surveys = surveyService.getSurveyAllVersions(studyId, surveyGuid);
        verifySurveyIsInStudy(session, surveys);
        return okResult(surveys);
    }
    
    public Result createSurvey() throws Exception {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        StudyIdentifier studyId = session.getStudyIdentifier();
        
        Survey survey = parseJson(request(), Survey.class);
        survey.setStudyIdentifier(studyId.getIdentifier());
        
        survey = surveyService.createSurvey(survey);
        return createdResult(new GuidCreatedOnVersionHolderImpl(survey));
    }
    
    public Result versionSurvey(String surveyGuid, String createdOnString) throws Exception {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        StudyIdentifier studyId = session.getStudyIdentifier();
        
        Survey survey = getSurveyWithoutCacheInternal(surveyGuid, createdOnString, session);

        survey = surveyService.versionSurvey(survey);
        expireCache(surveyGuid, createdOnString, studyId);
        
        return createdResult(new GuidCreatedOnVersionHolderImpl(survey));
    }
    
    public Result updateSurvey(String surveyGuid, String createdOnString) throws Exception {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        StudyIdentifier studyId = session.getStudyIdentifier();
        
        // Just checking permission to access
        getSurveyWithoutCacheInternal(surveyGuid, createdOnString, session);
        
        // The parameters in the URL take precedence over anything declared in 
        // the object itself.
        Survey survey = parseJson(request(), Survey.class);
        survey.setGuid(surveyGuid);
        survey.setCreatedOn(DateUtils.convertToMillisFromEpoch(createdOnString));
        survey.setStudyIdentifier(studyId.getIdentifier());
        
        survey = surveyService.updateSurvey(survey);
        expireCache(surveyGuid, createdOnString, studyId);
        
        return okResult(new GuidCreatedOnVersionHolderImpl(survey));
    }
    
    public Result publishSurvey(String surveyGuid, String createdOnString) throws Exception {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        StudyIdentifier studyId = session.getStudyIdentifier();
         
        Survey survey = getSurveyWithoutCacheInternal(surveyGuid, createdOnString, session);
        
        survey = surveyService.publishSurvey(studyId, survey);
        expireCache(surveyGuid, createdOnString, studyId);
        
        return okResult(new GuidCreatedOnVersionHolderImpl(survey));
    }

    private Survey getSurveyWithoutCacheInternal(String surveyGuid, String createdOnString, UserSession session) {
        long createdOn = DateUtils.convertToMillisFromEpoch(createdOnString);
        GuidCreatedOnVersionHolder keys = new GuidCreatedOnVersionHolderImpl(surveyGuid, createdOn);
        
        Survey survey = surveyService.getSurvey(keys);
        verifySurveyIsInStudy(session, survey);
        return survey;
    }
    
    private Result getCachedSurveyInternal(String surveyGuid, String createdOnString, UserSession session) {
        long createdOn = DateUtils.convertToMillisFromEpoch(createdOnString);
        GuidCreatedOnVersionHolder keys = new GuidCreatedOnVersionHolderImpl(surveyGuid, createdOn);

        ViewCacheKey<Survey> cacheKey = viewCache.getCacheKey(Survey.class, surveyGuid, createdOnString,
                session.getStudyIdentifier().getIdentifier());
        
        String json = getView(cacheKey, session, () -> {
            return surveyService.getSurvey(keys);
        });

        return ok(json).as(JSON_MIME_TYPE);
    }
    
    private Result getCachedSurveyMostRecentlyPublishedInternal(String surveyGuid, UserSession session) {
        ViewCacheKey<Survey> cacheKey = viewCache.getCacheKey(Survey.class, surveyGuid, PUBLISHED_KEY,
                session.getStudyIdentifier().getIdentifier());
        
        String json = getView(cacheKey, session, () -> {
            return surveyService.getSurveyMostRecentlyPublishedVersion(session.getStudyIdentifier(), surveyGuid);
        });
        
        return ok(json).as(JSON_MIME_TYPE);
    }
    
    private String getView(ViewCacheKey<Survey> cacheKey, UserSession session, Supplier<Survey> supplier) {
        return viewCache.getView(cacheKey, () -> {
            Survey survey = supplier.get();
            verifySurveyIsInStudy(session, survey);
            return survey;
        });
    }
    
    private void canAccessSurvey(UserSession session) {
        boolean isDeveloper = session.getUser().isInRole(DEVELOPER);
        boolean isConsentedUser = session.getUser().doesConsent();

        if (isDeveloper || isConsentedUser) {
            return;
        }
        // An imperfect test, but normal users have no other roles, so for them, access 
        // is restricted because they have not consented.
        Set<Roles> roles = new HashSet<>(session.getUser().getRoles());
        roles.remove(TEST_USERS);
        if (session.getUser().getRoles().isEmpty()) {
            throw new ConsentRequiredException(session);
        }
        // Otherwise, for researchers and administrators, the issue is one of authorization.
        throw new UnauthorizedException();
    }
    
    private void verifySurveyIsInStudy(UserSession session,List<Survey> surveys) {
        if (!surveys.isEmpty()) {
            verifySurveyIsInStudy(session, surveys.get(0));
        }
    }
    
    private void verifySurveyIsInStudy(UserSession session, Survey survey) {
        // This can happen if the user has the right keys to a survey, but it's not in the user's study.
        if (survey == null) {
            throw new UnauthorizedException();
        }
        StudyIdentifier studyId = session.getStudyIdentifier();
        if (!session.getUser().isInRole(ADMIN) && 
            !survey.getStudyIdentifier().equals(studyId.getIdentifier())) {
            throw new UnauthorizedException();
        }
    }
    
    private void expireCache(String surveyGuid, String createdOnString, StudyIdentifier studyId) {
        // Don't screw around trying to figure out if *this* survey instance is the same survey
        // as the most recent or published version, expire all versions in the cache
        viewCache.removeView(viewCache.getCacheKey(Survey.class, surveyGuid, createdOnString, studyId.getIdentifier()));
        viewCache.removeView(viewCache.getCacheKey(Survey.class, surveyGuid, PUBLISHED_KEY, studyId.getIdentifier()));
        viewCache.removeView(viewCache.getCacheKey(Survey.class, surveyGuid, MOSTRECENT_KEY, studyId.getIdentifier()));
    }
    
}
