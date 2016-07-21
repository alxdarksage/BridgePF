package org.sagebionetworks.bridge.play.controllers;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.sagebionetworks.bridge.BridgeConstants.BRIDGE_SESSION_EXPIRE_IN_SECONDS;
import static org.sagebionetworks.bridge.BridgeConstants.SESSION_TOKEN_HEADER;
import static org.sagebionetworks.bridge.dao.ParticipantOption.LANGUAGES;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Locale.LanguageRange;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import org.sagebionetworks.bridge.Roles;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.config.BridgeConfig;
import org.sagebionetworks.bridge.exceptions.UnsupportedVersionException;
import org.sagebionetworks.bridge.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.exceptions.NotAuthenticatedException;
import org.sagebionetworks.bridge.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.ClientInfo;
import org.sagebionetworks.bridge.models.CriteriaContext;
import org.sagebionetworks.bridge.models.Metrics;
import org.sagebionetworks.bridge.models.ResourceList;
import org.sagebionetworks.bridge.models.StatusMessage;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.play.interceptors.RequestUtils;
import org.sagebionetworks.bridge.services.AuthenticationService;
import org.sagebionetworks.bridge.services.ParticipantOptionsService;
import org.sagebionetworks.bridge.services.StudyService;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import play.cache.Cache;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http.Cookie;
import play.mvc.Http.Request;
import play.mvc.Result;

import com.amazonaws.util.Throwables;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;

public abstract class BaseController extends Controller {

    private final static Logger LOG = LoggerFactory.getLogger(BaseController.class);
    
    protected final static ObjectMapper MAPPER = BridgeObjectMapper.get();

    CacheProvider cacheProvider;
    
    BridgeConfig bridgeConfig;
    
    ParticipantOptionsService optionsService;

    StudyService studyService;

    AuthenticationService authenticationService;

    @Autowired
    final void setBridgeConfig(BridgeConfig bridgeConfig) {
        this.bridgeConfig = bridgeConfig;
    }

    @Autowired
    final void setCacheProvider(CacheProvider cacheProvider) {
        this.cacheProvider = cacheProvider;
    }

    @Autowired
    final void setStudyService(StudyService studyService) {
        this.studyService = studyService;
    }
    
    @Autowired
    final void setParticipantOptionsService(ParticipantOptionsService optionsService) {
        this.optionsService = optionsService;
    }

    @Autowired
    final void setAuthenticationService(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * Returns a session. Will not throw exception if user is not authorized or has not consented to research.
     * @return session if it exists, or null otherwise.
     */
    UserSession getSessionIfItExists() {
        final String sessionToken = getSessionToken();
        if (StringUtils.isBlank(sessionToken)){
            return null;
        }
        final UserSession session = authenticationService.getSession(sessionToken);
        writeSessionInfoToMetrics(session);
        return session;
    }

    /**
     * Retrieve user's session using the Bridge-Session header or cookie, throwing an exception if the session doesn't
     * exist (user not authorized), consent has not been given or the client app version is not supported.
     */
    UserSession getAuthenticatedAndConsentedSession() throws NotAuthenticatedException, ConsentRequiredException, UnsupportedVersionException {
        return getConsentedOrInRoleSession();
    }

    /**
     * Retrieve user's session and validate that they are either 1) a participant who has consented to research, 
     * OR 2) a user in a specific role.
     */
    UserSession getConsentedOrInRoleSession(Roles... roles) {
        UserSession session = getAuthenticatedSession();
        Study study = studyService.getStudy(session.getStudyIdentifier());        
        verifySupportedVersionOrThrowException(study);
        if (roles != null) {
            for (Roles role : roles) {
                if (session.isInRole(role)) {
                    return session;
                }
            }
        }
        if (!session.doesConsent()) {
            throw new ConsentRequiredException(session);
        }
        return session;
    }
    
    /**
     * Retrieve a user's session or throw an exception if the user is not authenticated. 
     * User does not have to give consent. If roles are provided, user must have one of 
     * the specified roles or an authorization exception will be thrown.
     */
    UserSession getAuthenticatedSession(Roles... roles) {
        final UserSession session = getSessionIfItExists();
        if (session == null || !session.isAuthenticated()) {
            throw new NotAuthenticatedException();
        }
        if (roles == null || roles.length == 0 || session.isInRole(roles)) {
            return session;
        }
        throw new UnauthorizedException();
    }

    void setSessionToken(String sessionToken) {
        response().setCookie(SESSION_TOKEN_HEADER, sessionToken, BRIDGE_SESSION_EXPIRE_IN_SECONDS, "/");
    }

    void updateSession(UserSession session) {
        cacheProvider.setUserSession(session);
        setSessionToken(session.getSessionToken());
    }

    /** Package-scoped to make available in unit tests. */
    String getSessionToken() {
        String[] session = request().headers().get(SESSION_TOKEN_HEADER);
        if (session == null || session.length == 0 || session[0].isEmpty()) {
            Cookie sessionCookie = request().cookie(SESSION_TOKEN_HEADER);
            if (sessionCookie != null && sessionCookie.value() != null && !"".equals(sessionCookie.value())) {
                return sessionCookie.value();
            }
            return null;
        }
        return session[0];
    }
    
    void verifySupportedVersionOrThrowException(Study study) throws UnsupportedVersionException {
        ClientInfo clientInfo = getClientInfoFromUserAgentHeader();
        if (!clientInfo.isSupportedAppVersion(study.getMinSupportedAppVersions().get(clientInfo.getOsName()))) {
        	throw new UnsupportedVersionException(clientInfo);
        }
    }

    /**
     * Once we acquire a language for a user, we save it and use that language going forward. Changing their 
     * language in the host operating system will not change the language they are using (since changing the 
     * language might change their consent state). If they change their language by updating their UserProfile, 
     * then they may have to reconsent in the new language they are using for the study. Any warnings to 
     * that effect will need to be included in the application.
     */
    LinkedHashSet<String> getLanguages(UserSession session) {
        StudyParticipant participant = session.getParticipant();
        if (!participant.getLanguages().isEmpty()) {
            return participant.getLanguages();
        }
        LinkedHashSet<String> languages = getLanguagesFromAcceptLanguageHeader();
        if (!languages.isEmpty()) {
            optionsService.setOrderedStringSet(
                    session.getStudyIdentifier(), session.getHealthCode(), LANGUAGES, languages);
            
            session.setParticipant(new StudyParticipant.Builder()
                    .copyOf(participant).withLanguages(languages).build());
            updateSession(session);
        }
        return languages;
    }
    
    /**
     * Returns languages in the order of their quality rating in the original LanguageRange objects 
     * that are created from the Accept-Language header (first item in ordered set is the most-preferred 
     * language option).
     * @return
     */
    LinkedHashSet<String> getLanguagesFromAcceptLanguageHeader() {
        String acceptLanguageHeader = request().getHeader(ACCEPT_LANGUAGE);
        if (isNotBlank(acceptLanguageHeader)) {
            try {
                List<LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguageHeader);
                return ranges.stream().map(range -> {
                    return Locale.forLanguageTag(range.getRange()).getLanguage();
                }).collect(Collectors.toCollection(LinkedHashSet::new));
            } catch(IllegalArgumentException e) {
                // Accept-Language header was not properly formatted, do not throw an exception over 
                // a malformed header, just return that no languages were found.
                LOG.debug("Malformed Accept-Language header sent: " + acceptLanguageHeader);
            }
        }
        return new LinkedHashSet<>();
    }
    
    ClientInfo getClientInfoFromUserAgentHeader() {
        String userAgentHeader = request().getHeader(USER_AGENT);
        ClientInfo info = ClientInfo.fromUserAgentCache(userAgentHeader);
        
        LOG.debug("User-Agent: '"+userAgentHeader+"' converted to " + info);
    	return info;
    }
    
    CriteriaContext getCriteriaContext(StudyIdentifier studyId) {
        return new CriteriaContext.Builder()
            .withStudyIdentifier(studyId)
            .withLanguages(getLanguagesFromAcceptLanguageHeader())
            .withClientInfo(getClientInfoFromUserAgentHeader())
            .build();
    }
    
    CriteriaContext getCriteriaContext(UserSession session) {
        checkNotNull(session);
        
        return new CriteriaContext.Builder()
            .withLanguages(getLanguagesFromAcceptLanguageHeader())
            .withClientInfo(getClientInfoFromUserAgentHeader())
            .withHealthCode(session.getHealthCode())
            .withUserId(session.getId())
            .withUserDataGroups(session.getParticipant().getDataGroups())
            .withStudyIdentifier(session.getStudyIdentifier())
            .build();
    }
    
    Result okResult(String message) {
        return ok(Json.toJson(new StatusMessage(message)));
    }

    Result okResult(Object obj) {
        return ok((JsonNode)MAPPER.valueToTree(obj));
    }
    
    <T> Result okResult(List<T> list) {
        return ok((JsonNode)MAPPER.valueToTree(new ResourceList<T>(list)));
    }
    
    Result createdResult(String message) throws Exception {
        return created(Json.toJson(new StatusMessage(message)));
    }
    
    Result createdResult(Object obj) throws Exception {
        return created((JsonNode)MAPPER.valueToTree(obj));
    }
    
    Result acceptedResult(String message) {
        return status(202, Json.toJson(new StatusMessage(message)));
    }
    
    // This is needed or tests fail. It appears to be a bug in Play Framework,
    // that the asJson() method doesn't return a node in that context, possibly
    // because the root object in the JSON is an array (which is legal). 
    JsonNode requestToJSON(Request request) {
        try {
            JsonNode node = request.body().asJson();
            if (node == null) {
                node = MAPPER.readTree(request().body().asText());
            }
            return node;
        } catch(Throwable e) {
            if (Throwables.getRootCause(e) instanceof InvalidEntityException) {
                throw (InvalidEntityException)Throwables.getRootCause(e);
            }
            throw new InvalidEntityException("Expected JSON in the request body is missing or malformed");
        }
    }

    /**
     * Static utility function that parses the JSON from the given request as the given class. This is a wrapper around
     * Jackson.
     *
     * @param request
     *         Play framework request
     * @param clazz
     *         class to parse the JSON as
     * @return object parsed from JSON, will be non-null
     */
    static @Nonnull <T> T parseJson(Request request, Class<? extends T> clazz) {
        try {
            // Calling request.body() twice is safe. (Has been confirmed using "play debug" and stepping through this
            // code in a debugger.)
            // Whether asText() or asJson() works depends on the content-type header of the request
            // asText() returns data if the content-type is text/plain. asJson() returns data if the content-type is
            // text/json or application/json.
            String jsonText = request.body().asText();
            if (!Strings.isNullOrEmpty(jsonText)) {
                return MAPPER.readValue(jsonText, clazz);
            }

            JsonNode jsonNode = request.body().asJson();
            if (jsonNode != null) {
                return MAPPER.convertValue(jsonNode, clazz);
            }
        } catch (Throwable ex) {
            if (Throwables.getRootCause(ex) instanceof InvalidEntityException) {
                throw (InvalidEntityException)Throwables.getRootCause(ex);
            }
            throw new InvalidEntityException("Error parsing JSON in request body: " + ex.getMessage());    
        }
        throw new InvalidEntityException("Expected JSON in the request body is missing");
    }
    
    /**
     * Retrieves the metrics object from the cache. Can be null if the metrics is not in the cache.
     */
    Metrics getMetrics() {
        final String requestId = RequestUtils.getRequestId(request());
        final String cacheKey = Metrics.getCacheKey(requestId);
        return (Metrics)Cache.get(cacheKey);
    }

    /** Writes the user's stormpath token, internal session ID, and study ID to the metrics. */
    protected void writeSessionInfoToMetrics(UserSession session) {
        Metrics metrics = getMetrics();
        if (metrics != null && session != null) {
            metrics.setSessionId(session.getInternalSessionToken());
            metrics.setUserId(session.getId());
            metrics.setStudy(session.getStudyIdentifier().getIdentifier());
        }
    }
}
