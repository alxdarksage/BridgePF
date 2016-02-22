package org.sagebionetworks.bridge.play.controllers;

import static org.apache.http.HttpHeaders.ACCEPT_LANGUAGE;
import static org.apache.http.HttpHeaders.USER_AGENT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.bridge.TestUtils.mockPlayContext;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import org.junit.Test;

import play.mvc.Http;

import org.sagebionetworks.bridge.Roles;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.exceptions.UnsupportedVersionException;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.ClientInfo;
import org.sagebionetworks.bridge.models.accounts.Email;
import org.sagebionetworks.bridge.models.accounts.User;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyIdentifierImpl;
import org.sagebionetworks.bridge.play.controllers.BaseController;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

/** Test class for basic utility functions in BaseController. */
@SuppressWarnings("unchecked")
public class BaseControllerTest {
    private static final String DUMMY_JSON = "{\"dummy-key\":\"dummy-value\"}";

    @Test
    public void testParseJsonFromText() {
        // mock request
        Http.RequestBody mockBody = mock(Http.RequestBody.class);
        when(mockBody.asText()).thenReturn(DUMMY_JSON);

        Http.Request mockRequest = mock(Http.Request.class);
        when(mockRequest.body()).thenReturn(mockBody);

        // execute and validate
        Map<String, String> resultMap = BaseController.parseJson(mockRequest, Map.class);
        assertEquals(1, resultMap.size());
        assertEquals("dummy-value", resultMap.get("dummy-key"));
    }

    @Test
    public void testParseJsonFromNode() throws Exception {
        // mock request
        Http.RequestBody mockBody = mock(Http.RequestBody.class);
        when(mockBody.asText()).thenReturn(null);
        when(mockBody.asJson()).thenReturn(BridgeObjectMapper.get().readTree(DUMMY_JSON));

        Http.Request mockRequest = mock(Http.Request.class);
        when(mockRequest.body()).thenReturn(mockBody);

        // execute and validate
        Map<String, String> resultMap = BaseController.parseJson(mockRequest, Map.class);
        assertEquals(1, resultMap.size());
        assertEquals("dummy-value", resultMap.get("dummy-key"));
    }

    @Test(expected = InvalidEntityException.class)
    public void testParseJsonError() {
        Http.Request mockRequest = mock(Http.Request.class);
        when(mockRequest.body()).thenThrow(RuntimeException.class);
        BaseController.parseJson(mockRequest, Map.class);
    }

    @Test(expected = InvalidEntityException.class)
    public void testParseJsonNoJson() throws Exception {
        // mock request
        Http.RequestBody mockBody = mock(Http.RequestBody.class);
        when(mockBody.asText()).thenReturn(null);
        when(mockBody.asJson()).thenReturn(null);

        Http.Request mockRequest = mock(Http.Request.class);
        when(mockRequest.body()).thenReturn(mockBody);

        // execute and validate
        BaseController.parseJson(mockRequest, Map.class);
    }
    
    @Test
    public void canRetrieveClientInfoObject() throws Exception {
        mockHeader(USER_AGENT, "Asthma/26 (Unknown iPhone; iPhone OS 9.0.2) BridgeSDK/4");
        
        ClientInfo info = new SchedulePlanController().getClientInfoFromUserAgentHeader();
        assertEquals("Asthma", info.getAppName());
        assertEquals(26, info.getAppVersion().intValue());
        assertEquals("iPhone OS", info.getOsName());
        assertEquals("9.0.2", info.getOsVersion());
        assertEquals("BridgeSDK", info.getSdkName());
        assertEquals(4, info.getSdkVersion().intValue());
    }
    
    @Test
    public void doesNotThrowErrorWhenUserAgentStringInvalid() throws Exception {
        mockHeader(USER_AGENT, 
                "Amazon Route 53 Health Check Service; ref:c97cd53f-2272-49d6-a8cd-3cd658d9d020; report http://amzn.to/1vsZADi");
        
        ClientInfo info = new SchedulePlanController().getClientInfoFromUserAgentHeader();
        assertNull(info.getAppName());
        assertNull(info.getAppVersion());
        assertNull(info.getOsName());
        assertNull(info.getOsVersion());
        assertNull(info.getSdkName());
        assertNull(info.getSdkVersion());
    }
    
    @Test (expected = UnsupportedVersionException.class)
    public void testInvalidSupportedVersionThrowsException() throws Exception {
        mockHeader(USER_AGENT, "Asthma/26 (Unknown iPhone; iPhone OS 9.0.2) BridgeSDK/4");
        
        HashMap<String, Integer> map =new HashMap<>();
        map.put("iPhone OS", 28);
        
        Study study = mock(Study.class);
        when(study.getMinSupportedAppVersions()).thenReturn(map);
        
        SchedulePlanController controller = new SchedulePlanController();
        controller.verifySupportedVersionOrThrowException(study);

    }
    
    @Test
    public void testValidSupportedVersionDoesNotThrowException() throws Exception {
        mockHeader(USER_AGENT, "Asthma/26 (Unknown iPhone; iPhone OS 9.0.2) BridgeSDK/4");
        
        HashMap<String, Integer> map =new HashMap<>();
        map.put("iPhone OS", 25);
        
        Study study = mock(Study.class);
        when(study.getMinSupportedAppVersions()).thenReturn(map);
        
        SchedulePlanController controller = new SchedulePlanController();
        controller.verifySupportedVersionOrThrowException(study);
    }
    
    @Test
    public void testNullSupportedVersionDoesNotThrowException() throws Exception {
        mockHeader(USER_AGENT, "Asthma/26 (Unknown iPhone; iPhone OS 9.0.2) BridgeSDK/4");
        
        HashMap<String, Integer> map =new HashMap<>();
        
        Study study = mock(Study.class);
        when(study.getMinSupportedAppVersions()).thenReturn(map);
        
        SchedulePlanController controller = new SchedulePlanController();
        controller.verifySupportedVersionOrThrowException(study);
    }
    
    @Test
    public void testUnknownOSDoesNotThrowException() throws Exception {
        mockHeader(USER_AGENT, "Asthma/26 BridgeSDK/4");
        
        HashMap<String, Integer> map =new HashMap<>();
        map.put("iPhone OS", 25);
        
        Study study = mock(Study.class);
        when(study.getMinSupportedAppVersions()).thenReturn(map);
        
        SchedulePlanController controller = new SchedulePlanController();
        controller.verifySupportedVersionOrThrowException(study);
    }
    
    @Test(expected = UnauthorizedException.class)
    public void roleEnforcedWhenRetrievingSession() throws Exception {
        mockPlayContext();
        
        SchedulePlanController controller = spy(new SchedulePlanController());
        User user = new User();
        user.setRoles(Sets.newHashSet(Roles.RESEARCHER));
        
        UserSession session = new UserSession();
        session.setUser(user);
        doReturn(session).when(controller).getAuthenticatedSession();
        
        // This method, upon confronting the fact that the user does not have this role, 
        // throws an UnauthorizedException.
        controller.getAuthenticatedSession(Roles.ADMIN);
    }
    
    @Test
    public void canRetrieveLanguagesFromAcceptHeader() throws Exception {
        BaseController controller = new SchedulePlanController();
        
        mockPlayContext();
        
        // with no accept language header at all, things don't break;
        LinkedHashSet<String> langs = controller.getLanguagesFromAcceptLanguageHeader();
        // testing this because the rest of these tests will use ImmutableSet.of()
        assertTrue(langs instanceof LinkedHashSet); 
        assertEquals(ImmutableSet.of(), langs);
        
        mockHeader(ACCEPT_LANGUAGE, "de-de;q=0.4,de;q=0.2,en-ca,en;q=0.8,en-us;q=0.6");
        
        langs = controller.getLanguagesFromAcceptLanguageHeader();
            
        LinkedHashSet<String> set = Sets.newLinkedHashSet();
        set.add("en");
        set.add("de");
        assertEquals(set, langs);

        mockHeader(ACCEPT_LANGUAGE, null);
        langs = controller.getLanguagesFromAcceptLanguageHeader();
        assertEquals(ImmutableSet.of(), langs);
            
        mockHeader(ACCEPT_LANGUAGE, "");
        langs = controller.getLanguagesFromAcceptLanguageHeader();
        assertEquals(ImmutableSet.of(), langs);
            
        mockHeader(ACCEPT_LANGUAGE, "en-US");
        langs = controller.getLanguagesFromAcceptLanguageHeader();
        assertEquals(ImmutableSet.of("en"), langs);
            
        mockHeader(ACCEPT_LANGUAGE, "FR,en-US");
        langs = controller.getLanguagesFromAcceptLanguageHeader();
        assertEquals(ImmutableSet.of("fr","en"), langs);
    }
    
    @Test
    public void testParseJsonTakesNode() throws Exception {
        JsonNode jsonNode = BridgeObjectMapper.get().readTree(
                TestUtils.makeJson("{'email':'test@test.com','study':'studyId'}"));
        
        BaseController controller = new SchedulePlanController();
        
        Email email = controller.parseJson(jsonNode, Email.class);
        assertEquals("test@test.com", email.getEmail());
        assertEquals(new StudyIdentifierImpl("studyId"), email.getStudyIdentifier());
    }
    
    private void mockHeader(String header, String value) throws Exception {
        Http.Request mockRequest = mock(Http.Request.class);
        when(mockRequest.getHeader(header)).thenReturn(value);
        mockPlayContext(mockRequest);
    }

}
