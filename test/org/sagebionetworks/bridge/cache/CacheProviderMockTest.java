package org.sagebionetworks.bridge.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Map;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import org.sagebionetworks.bridge.BridgeConstants;
import org.sagebionetworks.bridge.Roles;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.config.BridgeConfigFactory;
import org.sagebionetworks.bridge.config.Environment;
import org.sagebionetworks.bridge.crypto.AesGcmEncryptor;
import org.sagebionetworks.bridge.crypto.Encryptor;
import org.sagebionetworks.bridge.dao.ParticipantOption.SharingScope;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.accounts.ConsentStatus;
import org.sagebionetworks.bridge.models.accounts.SignIn;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.subpopulations.SubpopulationGuid;
import org.sagebionetworks.bridge.redis.JedisOps;
import org.sagebionetworks.bridge.redis.JedisTransaction;
import org.sagebionetworks.bridge.redis.RedisKey;

import redis.clients.jedis.JedisPool;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

@RunWith(MockitoJUnitRunner.class)
public class CacheProviderMockTest {

    private static final Encryptor ENCRYPTOR = new AesGcmEncryptor(BridgeConfigFactory.getConfig().getProperty("bridge.healthcode.redis.key"));
    private static final String USER_ID = "userId";
    private static final String SESSION_TOKEN = "sessionToken";
    private static final String ENCRYPTED_SESSION_TOKEN = "TFMkaVFKPD48WissX0bgcD3esBMEshxb3MVgKxHnkXLSEPN4FQMKc01tDbBAVcXx94kMX6ckXVYUZ8wx4iICl08uE+oQr9gorE1hlgAyLAM=";
    private static final String DECRYPTED_SESSION_TOKEN = "ccea2978-f5b9-4377-8194-f887a3e2a19b";
    
    private CacheProvider cacheProvider;
    
    @Mock
    private JedisTransaction oldTransaction;

    @Mock
    private JedisTransaction newTransaction;
    
    @Mock
    private JedisOps jedisOps;
    
    @Mock
    private JedisOps newJedisOps;
    
    @Before
    public void before() {
        mockTransaction(oldTransaction);
        mockTransaction(newTransaction);
        
        when(jedisOps.getTransaction()).thenReturn(oldTransaction);
        when(newJedisOps.getTransaction()).thenReturn(newTransaction);
        
        String userKey = RedisKey.USER_SESSION.getRedisKey(USER_ID);
        when(jedisOps.get(userKey)).thenReturn(SESSION_TOKEN);
        
        cacheProvider = new CacheProvider();
        cacheProvider.setOldJedisOps(jedisOps);
        cacheProvider.setNewJedisOps(newJedisOps);
        cacheProvider.setBridgeObjectMapper(BridgeObjectMapper.get());
    }

    private void mockTransaction(JedisTransaction trans) {
        when(trans.setex(any(String.class), anyInt(), any(String.class))).thenReturn(trans);
        when(trans.expire(any(String.class), anyInt())).thenReturn(trans);
        when(trans.del(any(String.class))).thenReturn(trans);
        when(trans.exec()).thenReturn(Arrays.asList((Object)"OK", "OK"));
    }

    @Test
    public void testSetUserSession() throws Exception {
        StudyParticipant participant = new StudyParticipant.Builder()
                .withEmail("userEmail")
                .withId(USER_ID)
                .withHealthCode("healthCode").build();
        
        UserSession session = new UserSession(participant);
        session.setSessionToken(SESSION_TOKEN);
        cacheProvider.setUserSession(session);

        String sessionKey = RedisKey.SESSION.getRedisKey(SESSION_TOKEN);
        String userKey = RedisKey.USER_SESSION.getRedisKey(USER_ID);
        
        verify(newTransaction).setex(eq(sessionKey), anyInt(), anyString());
        verify(newTransaction).setex(eq(userKey), anyInt(), eq(SESSION_TOKEN));
        verify(newTransaction).exec();
    }

    @Test
    public void testSetUserSessionNullSessionToken() throws Exception {
        StudyParticipant participant = new StudyParticipant.Builder()
                .withEmail("userEmail")
                .withId(USER_ID)
                .withHealthCode("healthCode").build();
        
        UserSession session = new UserSession(participant);
        try {
            cacheProvider.setUserSession(session);
        } catch(NullPointerException e) {
            assertTrue("NPE expected.", true);
        } catch(Throwable e) {
            fail(e.getMessage());
        }
        String sessionKey = RedisKey.SESSION.getRedisKey(SESSION_TOKEN);
        String userKey = RedisKey.USER_SESSION.getRedisKey(USER_ID);
        
        verify(newTransaction, never()).setex(eq(sessionKey), anyInt(), anyString());
        verify(newTransaction, never()).setex(eq(userKey), anyInt(), eq(SESSION_TOKEN));
        verify(newTransaction, never()).exec();
        
        verify(oldTransaction, never()).setex(eq(sessionKey), anyInt(), anyString());
        verify(oldTransaction, never()).setex(eq(userKey), anyInt(), eq(SESSION_TOKEN));
        verify(oldTransaction, never()).exec();
    }

    @Test
    public void testSetUserSessionNullUser() throws Exception {
        UserSession session = new UserSession();
        session.setSessionToken(SESSION_TOKEN);
        try {
            cacheProvider.setUserSession(session);
        } catch(NullPointerException e) {
            assertTrue("NPE expected.", true);
        } catch(Throwable e) {
            fail(e.getMessage());
        }
        String sessionKey = RedisKey.SESSION.getRedisKey(SESSION_TOKEN);
        String userKey = RedisKey.USER_SESSION.getRedisKey(USER_ID);
        
        verify(newTransaction, never()).setex(eq(sessionKey), anyInt(), anyString());
        verify(newTransaction, never()).setex(eq(userKey), anyInt(), eq(SESSION_TOKEN));
        verify(newTransaction, never()).exec();
        
        verify(oldTransaction, never()).setex(eq(sessionKey), anyInt(), anyString());
        verify(oldTransaction, never()).setex(eq(userKey), anyInt(), eq(SESSION_TOKEN));
        verify(oldTransaction, never()).exec();
    }

    @Test
    public void testSetUserSessionNullUserId() throws Exception {
        StudyParticipant participant = new StudyParticipant.Builder()
                .withEmail("userEmail")
                .withHealthCode("healthCode").build();        
        
        UserSession session = new UserSession(participant);
        session.setSessionToken(SESSION_TOKEN);
        try {
            cacheProvider.setUserSession(session);
        } catch(NullPointerException e) {
            assertTrue("NPE expected.", true);
        } catch(Throwable e) {
            fail(e.getMessage());
        }
        String sessionKey = RedisKey.SESSION.getRedisKey(SESSION_TOKEN);
        String userKey = RedisKey.USER_SESSION.getRedisKey(USER_ID);
        
        verify(newTransaction, never()).setex(eq(sessionKey), anyInt(), anyString());
        verify(newTransaction, never()).setex(eq(userKey), anyInt(), eq(SESSION_TOKEN));
        verify(newTransaction, never()).exec();
        
        verify(oldTransaction, never()).setex(eq(sessionKey), anyInt(), anyString());
        verify(oldTransaction, never()).setex(eq(userKey), anyInt(), eq(SESSION_TOKEN));
        verify(oldTransaction, never()).exec();
    }

    @Test
    public void testGetUserSessionByUserId() throws Exception {
        CacheProvider mockCacheProvider = spy(cacheProvider);
        mockCacheProvider.getUserSessionByUserId(USER_ID);
        verify(mockCacheProvider, times(1)).getUserSession(SESSION_TOKEN);
    }

    @Test
    public void testRemoveSession() {
        StudyParticipant participant = new StudyParticipant.Builder().withId(USER_ID).build();

        UserSession session = new UserSession(participant);
        session.setSessionToken(SESSION_TOKEN);
        
        cacheProvider.removeSession(session);
        cacheProvider.getUserSession(SESSION_TOKEN);
        String sessionKey = RedisKey.SESSION.getRedisKey(SESSION_TOKEN);
        String userKey = RedisKey.USER_SESSION.getRedisKey(USER_ID);
        
        verify(newTransaction).del(sessionKey);
        verify(newTransaction).del(userKey);
        verify(newTransaction).exec();
        
        verify(oldTransaction).del(sessionKey);
        verify(oldTransaction).del(userKey);
        verify(oldTransaction).exec();
    }

    @Test
    public void testRemoveSessionByUserId() {
        cacheProvider.removeSessionByUserId(USER_ID);
        String sessionKey = RedisKey.SESSION.getRedisKey(SESSION_TOKEN);
        String userKey = RedisKey.USER_SESSION.getRedisKey(USER_ID);
        
        verify(oldTransaction).del(sessionKey);
        verify(oldTransaction).del(userKey);
        verify(oldTransaction).exec();
    }

    @Test
    public void addAndRemoveViewFromCacheProvider() throws Exception {
        final CacheProvider simpleCacheProvider = new CacheProvider();
        simpleCacheProvider.setOldJedisOps(getJedisOps());
        simpleCacheProvider.setNewJedisOps(getJedisOps());
        simpleCacheProvider.setBridgeObjectMapper(BridgeObjectMapper.get());

        final Study study = TestUtils.getValidStudy(CacheProviderMockTest.class);
        study.setIdentifier("test");
        study.setName("This is a test study");
        String json = BridgeObjectMapper.get().writeValueAsString(study);
        assertTrue(json != null && json.length() > 0);

        final String cacheKey = study.getIdentifier() + ":Study";
        simpleCacheProvider.setString(cacheKey, json, BridgeConstants.BRIDGE_VIEW_EXPIRE_IN_SECONDS);

        String cachedString = simpleCacheProvider.getString(cacheKey);
        assertEquals(json, cachedString);

        // Remove something that's not the key
        simpleCacheProvider.removeString(cacheKey+"2");
        cachedString = simpleCacheProvider.getString(cacheKey);
        assertEquals(json, cachedString);

        simpleCacheProvider.removeString(cacheKey);
        cachedString = simpleCacheProvider.getString(cacheKey);
        assertNull(cachedString);
    }

    @Test
    public void newUserSessionDeserializes() {
        String json = TestUtils.createJson("{'authenticated':true,"+
                "'environment':'local',"+
                "'sessionToken':'"+DECRYPTED_SESSION_TOKEN+"',"+
                "'internalSessionToken':'4f0937a5-6ebf-451b-84bc-fbf649b9e93c',"+
                "'studyIdentifier':{'identifier':'api',"+
                    "'type':'StudyIdentifier'},"+
                "'consentStatuses':{"+
                    "'api':{'name':'Default Consent Group',"+
                        "'subpopulationGuid':'api',"+
                        "'required':true,"+
                        "'consented':false,"+
                        "'signedMostRecentConsent':true,"+
                        "'type':'ConsentStatus'}},"+
                "'participant':{'firstName':'Bridge',"+
                    "'lastName':'IT',"+
                    "'email':'bridgeit@sagebase.org',"+
                    "'sharingScope':'no_sharing',"+
                    "'notifyByEmail':false,"+
                    "'externalId':'ABC',"+
                    "'dataGroups':['group1'],"+
                    "'encryptedHealthCode':'"+ENCRYPTED_SESSION_TOKEN+"',"+
                    "'attributes':{},"+
                    "'consentHistories':{},"+
                    "'roles':['admin'],"+
                    "'languages':['en','fr'],"+
                    "'createdOn':'2016-04-21T16:48:22.386Z',"+
                    "'id':'6gq4jGXLmAxVbLLmVifKN4',"+
                    "'type':'StudyParticipant'},"+
                "'type':'UserSession'}");

        assertSession(json);
    }
    
    @Test
    public void setSignIn() throws Exception {
        SignIn signIn = new SignIn.Builder().withStudy("study").withEmail("email@email.com").build();
        String ser = BridgeObjectMapper.get().writeValueAsString(signIn);
        
        cacheProvider.setSignIn("token", "reverseLookupKey", signIn, 300);
        
        verify(newTransaction).setex("token", 300, ser);
        verify(newTransaction).setex("reverseLookupKey", 300, "token");
    }
    
    @Test
    public void hasSignInTokenOK() throws Exception {
        when(newJedisOps.get("study:email@email.com")).thenReturn("token");
        
        String token = cacheProvider.hasSignInToken("study:email@email.com");
        assertEquals("token", token);
    }
    
    @Test
    public void hasSignInTokenNoToken() {
        when(newJedisOps.get("study:email@email.com")).thenReturn(null);
        
        String token = cacheProvider.hasSignInToken("study:email@email.com");
        assertNull(token);
    }
    
    @Test
    public void getSignIn() throws Exception {
        SignIn signIn = new SignIn.Builder().withStudy("study").withEmail("email@email.com").build();
        String ser = BridgeObjectMapper.get().writeValueAsString(signIn);
        
        when(newJedisOps.get("token")).thenReturn(ser);
        
        SignIn retrieved = cacheProvider.getSignIn("token");
        
        assertEquals("study", retrieved.getStudyId());
        assertEquals("email@email.com", retrieved.getEmail());
    }
    
    @Test
    public void removeSignIn() {
        cacheProvider.removeSignIn("token", "reverseLookupKey");
        
        verify(newJedisOps).del("token");
        verify(newJedisOps).del("reverseLookupKey");
    }

    private void assertSession(String json) {
        JedisOps jedisOps = mock(JedisOps.class);
        
        String sessionKey = RedisKey.SESSION.getRedisKey("sessionToken");
        doReturn(sessionKey).when(jedisOps).get("sessionToken");
        doReturn(newTransaction).when(jedisOps).getTransaction(sessionKey);
        doReturn(json).when(jedisOps).get(sessionKey);
        
        cacheProvider.setOldJedisOps(jedisOps);
        cacheProvider.setBridgeObjectMapper(BridgeObjectMapper.get());
        
        UserSession session = cacheProvider.getUserSession("sessionToken");

        assertTrue(session.isAuthenticated());
        assertEquals(Environment.LOCAL, session.getEnvironment());
        assertEquals(DECRYPTED_SESSION_TOKEN, session.getSessionToken());
        assertEquals("4f0937a5-6ebf-451b-84bc-fbf649b9e93c", session.getInternalSessionToken());
        assertEquals("6gq4jGXLmAxVbLLmVifKN4", session.getId());
        assertEquals("api", session.getStudyIdentifier().getIdentifier());
        
        StudyParticipant participant = session.getParticipant();
        assertEquals("Bridge", participant.getFirstName());
        assertEquals("IT", participant.getLastName());
        assertEquals("bridgeit@sagebase.org", participant.getEmail());
        assertEquals(SharingScope.NO_SHARING, participant.getSharingScope());
        assertEquals(DateTime.parse("2016-04-21T16:48:22.386Z"), participant.getCreatedOn());
        assertEquals(Sets.newHashSet(Roles.ADMIN), participant.getRoles());
        assertEquals(Sets.newHashSet("en","fr"), participant.getLanguages());
        assertEquals("ABC", participant.getExternalId());
        
        assertEquals(participant.getHealthCode(), ENCRYPTOR.decrypt(ENCRYPTED_SESSION_TOKEN));
        
        SubpopulationGuid apiGuid = SubpopulationGuid.create("api");
        Map<SubpopulationGuid,ConsentStatus> consentStatuses = session.getConsentStatuses();
        ConsentStatus status = consentStatuses.get(apiGuid);
        assertEquals("Default Consent Group", status.getName());
        assertEquals(apiGuid.getGuid(), status.getSubpopulationGuid());
        assertTrue(status.getSignedMostRecentConsent());
        assertTrue(status.isRequired());
        assertFalse(status.isConsented());
    }
    
    private JedisOps getJedisOps() {
        return new JedisOps(new JedisPool()) {
            private Map<String,String> map = Maps.newHashMap();
            @Override
            public Long expire(final String key, final int seconds) {
                return 1L;
            }
            @Override
            public String setex(final String key, final int seconds, final String value) {
                map.put(key, value);
                return "OK";
            }
            @Override
            public Long setnx(final String key, final String value) {
                map.put(key, value);
                return 1L;
            }
            @Override
            public String get(final String key) {
                return map.get(key);
            }
            @Override
            public Long del(final String... keys) {
                for (String key : keys) {
                    map.remove(key);
                }
                return (long)keys.length;
            }
        };
    }
}
