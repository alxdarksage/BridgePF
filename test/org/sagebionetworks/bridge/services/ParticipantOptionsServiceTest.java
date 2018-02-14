package org.sagebionetworks.bridge.services;

import static org.sagebionetworks.bridge.TestConstants.TEST_STUDY;
import static org.sagebionetworks.bridge.dao.ParticipantOption.DATA_GROUPS;
import static org.sagebionetworks.bridge.dao.ParticipantOption.EMAIL_NOTIFICATIONS;
import static org.sagebionetworks.bridge.dao.ParticipantOption.EXTERNAL_IDENTIFIER;
import static org.sagebionetworks.bridge.dao.ParticipantOption.LANGUAGES;
import static org.sagebionetworks.bridge.dao.ParticipantOption.SHARING_SCOPE;
import static org.sagebionetworks.bridge.dao.ParticipantOption.TIME_ZONE;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.joda.time.DateTimeZone;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.dao.ParticipantOption;
import org.sagebionetworks.bridge.dao.ParticipantOption.SharingScope;
import org.sagebionetworks.bridge.dao.ParticipantOptionsDao;
import org.sagebionetworks.bridge.dynamodb.DynamoStudy;
import org.sagebionetworks.bridge.hibernate.HibernateAccountDao;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifier;
import org.sagebionetworks.bridge.models.accounts.ParticipantOptionsLookup;
import org.sagebionetworks.bridge.models.studies.Study;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

@RunWith(MockitoJUnitRunner.class)
public class ParticipantOptionsServiceTest {
    
    private static final String HEALTH_CODE = "AAA";
    
    private ParticipantOptionsService service;
    
    @Mock
    private ParticipantOptionsDao mockDao;
    @Mock
    private AccountDao mockAccountDao;
    @Mock
    private Account mockAccount;
    @Captor
    private ArgumentCaptor<Account> accountCaptor;
    
    @Before
    public void before() {
        service = new ParticipantOptionsService();
        mockDao = mock(ParticipantOptionsDao.class);
        mockAccountDao = mock(AccountDao.class);
        service.setParticipantOptionsDao(mockDao);
        service.setAccountDao(mockAccountDao);
        
        when(mockAccountDao.getAccount(any())).thenReturn(mockAccount);
        
        Study study = new DynamoStudy();
        study.setDataGroups(Sets.newHashSet("A","B","group1","group2","group3"));
    }

    @Test
    public void setBoolean() {
        service.setBoolean(TEST_STUDY, HEALTH_CODE, EMAIL_NOTIFICATIONS, true);

        verify(mockAccountDao).updateAccount(accountCaptor.capture(), eq(false));
        verify(mockAccount).setNotifyByEmail(Boolean.TRUE);
    }
    
    @Test
    public void getBoolean() {
        when(mockAccount.getNotifyByEmail()).thenReturn(Boolean.TRUE);
        when(mockDao.getOptions(HEALTH_CODE)).thenReturn(new ParticipantOptionsLookup(map(EMAIL_NOTIFICATIONS, "true")));
        
        assertTrue(service.getOptions(TEST_STUDY, HEALTH_CODE).getBoolean(EMAIL_NOTIFICATIONS));
    }
    
    @Test
    public void getBooleanNull() {
        when(mockAccount.getNotifyByEmail()).thenReturn(null);
        when(mockDao.getOptions(HEALTH_CODE)).thenReturn(new ParticipantOptionsLookup(map(EMAIL_NOTIFICATIONS, null)));
        
        assertTrue(service.getOptions(TEST_STUDY, HEALTH_CODE).getBoolean(EMAIL_NOTIFICATIONS));
    }
    
    @Test
    public void setString() {
        ExternalIdentifier externalId = ExternalIdentifier.create(TEST_STUDY, "BBB");
        
        service.setString(TEST_STUDY, HEALTH_CODE, EXTERNAL_IDENTIFIER, externalId.getIdentifier());
        
        verify(mockAccountDao).updateAccount(accountCaptor.capture(), eq(true));
        verify(mockAccount).setExternalId("BBB");
    }
    
    @Test
    public void getString() {
        when(mockDao.getOptions(HEALTH_CODE)).thenReturn(new ParticipantOptionsLookup(map(EXTERNAL_IDENTIFIER, "BBB")));
        
        assertEquals("BBB", service.getOptions(TEST_STUDY, HEALTH_CODE).getString(EXTERNAL_IDENTIFIER));
    }
    
    @Test
    public void setEnum() {
        service.setEnum(TEST_STUDY, HEALTH_CODE, SHARING_SCOPE, SharingScope.SPONSORS_AND_PARTNERS);
        
        verify(mockAccountDao).updateAccount(accountCaptor.capture(), eq(false));
        verify(mockAccount).setSharingScope(SharingScope.SPONSORS_AND_PARTNERS);
    }
    
    @Test
    public void setTimeZone() {
        DateTimeZone zone = DateTimeZone.forOffsetHours(-8);
        
        service.setDateTimeZone(TEST_STUDY, HEALTH_CODE, TIME_ZONE, zone);
        
        verify(mockAccountDao).updateAccount(accountCaptor.capture(), eq(false));
        verify(mockAccount).setTimeZone(DateTimeZone.forOffsetHours(-8));
    }
    
    @Test
    public void setTimeZoneUTC() {
        service.setDateTimeZone(TEST_STUDY, HEALTH_CODE, TIME_ZONE, DateTimeZone.UTC);
        
        verify(mockAccountDao).updateAccount(accountCaptor.capture(), eq(false));
        verify(mockAccount).setTimeZone(DateTimeZone.UTC);
    }
    
    @Test
    public void getEnum() {
        when(mockDao.getOptions(HEALTH_CODE))
                .thenReturn(new ParticipantOptionsLookup(map(SHARING_SCOPE, "SPONSORS_AND_PARTNERS")));
        
        assertEquals(SharingScope.SPONSORS_AND_PARTNERS, service.getOptions(TEST_STUDY, HEALTH_CODE).getEnum(SHARING_SCOPE, SharingScope.class));
    }
    
    @Test
    public void getEnumNull() {
        when(mockDao.getOptions(HEALTH_CODE)).thenReturn(new ParticipantOptionsLookup(map(SHARING_SCOPE, null)));
        
        assertEquals(SharingScope.NO_SHARING, service.getOptions(TEST_STUDY, HEALTH_CODE).getEnum(SHARING_SCOPE, SharingScope.class));
    }
    
    @Test
    public void setStringSet() {
        Set<String> dataGroups = Sets.newHashSet("group1", "group2", "group3");

        service.setStringSet(TEST_STUDY, HEALTH_CODE, DATA_GROUPS, dataGroups);
        
        // Order of the set when serialized is indeterminate, it's a set
        verify(mockAccountDao).updateAccount(accountCaptor.capture(), eq(false));
        verify(mockAccount).setDataGroups(dataGroups);
    }
    
    @Test
    public void getStringSet() {
        when(mockDao.getOptions(HEALTH_CODE)).thenReturn(
                new ParticipantOptionsLookup(map(DATA_GROUPS, "group1,group2,group3")));
        
        Set<String> dataGroups = Sets.newHashSet("group1", "group2", "group3");
        
        assertEquals(dataGroups, service.getOptions(TEST_STUDY, HEALTH_CODE).getStringSet(DATA_GROUPS));
    }
    
    @Test
    public void getStringSetNull() {
        when(mockDao.getOptions(HEALTH_CODE)).thenReturn(
                new ParticipantOptionsLookup(map(DATA_GROUPS, null)));
        
        assertEquals(Sets.newHashSet(), service.getOptions(TEST_STUDY, HEALTH_CODE).getStringSet(DATA_GROUPS));
    }
    
    @
    Test
    public void getTimeZone() {
        when(mockDao.getOptions(HEALTH_CODE)).thenReturn(
                new ParticipantOptionsLookup(map(TIME_ZONE, "-08:00")));
        
        assertEquals(DateTimeZone.forOffsetHours(-8), service.getOptions(TEST_STUDY, HEALTH_CODE).getTimeZone(TIME_ZONE));
    }

    @Test
    public void deleteAllParticipantOptions() {
        service.deleteAllParticipantOptions(HEALTH_CODE);
        
        verify(mockDao).deleteAllOptions(HEALTH_CODE);
        verifyNoMoreInteractions(mockDao);
    }
    
    @Test
    public void getAllParticipantOptions() {
        Map<String,String> map = Maps.newHashMap();
        map.put(DATA_GROUPS.name(), "a,b,c");
        
        ParticipantOptionsLookup lookup = new ParticipantOptionsLookup(map);
        
        when(mockDao.getOptions(HEALTH_CODE)).thenReturn(lookup);
        
        ParticipantOptionsLookup result = service.getOptions(TEST_STUDY, HEALTH_CODE);
        assertEquals(lookup.getStringSet(DATA_GROUPS), result.getStringSet(DATA_GROUPS));
        
        verify(mockDao).getOptions(HEALTH_CODE);
        verifyNoMoreInteractions(mockDao);
    }
    
    @Test
    public void canSetLinkedHashSet() {
        LinkedHashSet<String> langs = TestUtils.newLinkedHashSet("fr","en","kl");
        
        when(mockAccount.getLanguages()).thenReturn(langs);
        when(mockAccount.getMigrationVersion()).thenReturn(HibernateAccountDao.CURRENT_MIGRATION_VERSION);
        
        LinkedHashSet<String> result = service.getOptions(TEST_STUDY, HEALTH_CODE).getOrderedStringSet(LANGUAGES);
        Iterator<String> i = result.iterator();
        assertEquals("fr", i.next());
        assertEquals("en", i.next());
        
        service.setOrderedStringSet(TEST_STUDY, HEALTH_CODE, LANGUAGES, langs);
        
        verify(mockAccountDao).updateAccount(accountCaptor.capture(), eq(false));
        verify(mockAccount).setLanguages(langs);
    }
    
    private Map<String,String> map(ParticipantOption option, String value) {
        Map<String,String> map = Maps.newHashMap();
        map.put(option.name(), value);
        return map;
    }
    
}
