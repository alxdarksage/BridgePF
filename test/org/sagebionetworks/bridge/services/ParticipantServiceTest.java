package org.sagebionetworks.bridge.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.bridge.dao.ParticipantOption.DATA_GROUPS;
import static org.sagebionetworks.bridge.dao.ParticipantOption.EMAIL_NOTIFICATIONS;
import static org.sagebionetworks.bridge.dao.ParticipantOption.EXTERNAL_IDENTIFIER;
import static org.sagebionetworks.bridge.dao.ParticipantOption.LANGUAGES;
import static org.sagebionetworks.bridge.dao.ParticipantOption.SHARING_SCOPE;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.dao.ParticipantOption;
import org.sagebionetworks.bridge.dao.ParticipantOption.SharingScope;
import org.sagebionetworks.bridge.dynamodb.DynamoStudy;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.AccountStatus;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifier;
import org.sagebionetworks.bridge.models.accounts.HealthId;
import org.sagebionetworks.bridge.models.accounts.ParticipantOptionsLookup;
import org.sagebionetworks.bridge.models.accounts.SignUp;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserConsentHistory;
import org.sagebionetworks.bridge.models.studies.PasswordPolicy;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.subpopulations.Subpopulation;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

@RunWith(MockitoJUnitRunner.class)
public class ParticipantServiceTest {

    private static final Set<String> STUDY_PROFILE_ATTRS = BridgeUtils.commaListToOrderedSet("attr1,attr2");
    private static final Set<String> STUDY_DATA_GROUPS = BridgeUtils.commaListToOrderedSet("group1,group2");
    private static final LinkedHashSet<String> USER_LANGUAGES = (LinkedHashSet<String>)BridgeUtils.commaListToOrderedSet("de,fr");
    private static final String EMAIL = "email@email.com";
    private static final Map<String,String> ATTRS = new ImmutableMap.Builder<String,String>().put("phone","123456789").build();
    private static final StudyParticipant PARTICIPANT = new StudyParticipant.Builder()
            .withFirstName("firstName")
            .withLastName("lastName")
            .withEmail(EMAIL)
            .withPassword("P@ssword1")
            .withSharingScope(SharingScope.ALL_QUALIFIED_RESEARCHERS)
            .withNotifyByEmail(true)
            .withDataGroups(STUDY_DATA_GROUPS)
            .withAttributes(ATTRS)
            .withLanguages(USER_LANGUAGES)
            .withStatus(AccountStatus.DISABLED)
            .withExternalId("POWERS").build();

    private static final Study STUDY = new DynamoStudy();
    static {
        STUDY.setIdentifier("test-study");
        STUDY.setHealthCodeExportEnabled(true);
        STUDY.setUserProfileAttributes(STUDY_PROFILE_ATTRS);
        STUDY.setDataGroups(STUDY_DATA_GROUPS);
        STUDY.setPasswordPolicy(PasswordPolicy.DEFAULT_PASSWORD_POLICY);
        STUDY.getUserProfileAttributes().add("phone");
    }
    
    private ParticipantService participantService;
    
    @Mock
    private AccountDao accountDao;
    
    @Mock
    private ParticipantOptionsService optionsService;
    
    @Mock
    private SubpopulationService subpopService;
    
    @Mock
    private HealthCodeService healthCodeService;
    
    @Mock
    private ConsentService consentService;
    
    @Mock
    private Account account;
    
    @Mock
    private HealthId healthId;
    
    @Mock
    private ParticipantOptionsLookup lookup;
    
    @Mock
    private CacheProvider cacheProvider;
    
    @Mock
    private ExternalIdService externalIdService;
    
    @Captor
    ArgumentCaptor<SignUp> signUpCaptor;
    
    @Captor
    ArgumentCaptor<Map<ParticipantOption,String>> optionsCaptor;
    
    @Captor
    ArgumentCaptor<Account> accountCaptor;
    
    @Before
    public void before() {
        STUDY.setExternalIdValidationEnabled(false);
        participantService = new ParticipantService();
        participantService.setAccountDao(accountDao);
        participantService.setParticipantOptionsService(optionsService);
        participantService.setSubpopulationService(subpopService);
        participantService.setHealthCodeService(healthCodeService);
        participantService.setUserConsent(consentService);
        participantService.setCacheProvider(cacheProvider);
        participantService.setExternalIdService(externalIdService);
    }
    
    @Test
    public void createParticipant() {
        STUDY.setExternalIdValidationEnabled(true);
        doReturn("healthId").when(account).getHealthId();
        doReturn(healthId).when(healthCodeService).getMapping("healthId");
        doReturn("healthCode").when(healthId).getCode();
        when(accountDao.signUp(eq(STUDY), any(), eq(false))).thenReturn(account);
        doReturn("id").when(account).getId();
        
        participantService.createParticipant(STUDY, PARTICIPANT);

        verify(externalIdService).reserveExternalId(STUDY, "POWERS");
        verify(externalIdService).assignExternalId(STUDY, "POWERS", "healthCode");
        
        verify(accountDao).signUp(eq(STUDY), signUpCaptor.capture(), eq(false));
        SignUp signUp = signUpCaptor.getValue();
        assertEquals("email@email.com", signUp.getEmail());
        assertEquals("P@ssword1", signUp.getPassword());
        
        verify(optionsService).setAllOptions(eq(STUDY.getStudyIdentifier()), eq("healthCode"), optionsCaptor.capture());
        Map<ParticipantOption, String> options = optionsCaptor.getValue();
        assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS.name(), options.get(SHARING_SCOPE));
        assertEquals("true", options.get(EMAIL_NOTIFICATIONS));
        // Because strict validation is enabled, we do not update this property along with the others, we
        // go through externalIdService
        assertNull(options.get(EXTERNAL_IDENTIFIER));
        assertEquals("group1,group2", options.get(DATA_GROUPS));
        assertEquals("de,fr", options.get(LANGUAGES));
        
        verify(accountDao).updateAccount(eq(STUDY), accountCaptor.capture());
        Account account = accountCaptor.getValue();
        verify(account).setFirstName("firstName");
        verify(account).setLastName("lastName");
        verify(account).setAttribute("phone", "123456789");
        // Not called on create
        verify(account, never()).setStatus(AccountStatus.DISABLED);
        
        // don't update cache
        verify(cacheProvider, never()).removeSessionByUserId("id");
    }
    
    // Or any other failure to reserve an externalId
    @Test
    public void createParticipantWithAssignedId() {
        STUDY.setExternalIdValidationEnabled(true);
        
        doThrow(new EntityAlreadyExistsException(ExternalIdentifier.create(STUDY, "AAA")))
            .when(externalIdService).reserveExternalId(STUDY, "POWERS");
        
        try {
            participantService.createParticipant(STUDY, PARTICIPANT);
            fail("Should have thrown exception");
        } catch(EntityAlreadyExistsException e) {
        }
        verify(externalIdService).reserveExternalId(STUDY, "POWERS");
        verifyNoMoreInteractions(accountDao);
        verifyNoMoreInteractions(optionsService);
        verifyNoMoreInteractions(healthCodeService);        
    }
    
    @Test
    public void createParticipantWithExternalIdValidation() {
        STUDY.setExternalIdValidationEnabled(true);
        doReturn("healthId").when(account).getHealthId();
        doReturn(healthId).when(healthCodeService).getMapping("healthId");
        doReturn("healthCode").when(healthId).getCode();
        when(accountDao.signUp(eq(STUDY), any(), eq(false))).thenReturn(account);
        
        participantService.createParticipant(STUDY, PARTICIPANT);
        verify(externalIdService).reserveExternalId(STUDY, "POWERS");
        // Do not set the externalId with the other options, go through the externalIdService
        verify(optionsService).setAllOptions(eq(STUDY.getStudyIdentifier()), eq("healthCode"), optionsCaptor.capture());
        Map<ParticipantOption,String> options = optionsCaptor.getValue();
        assertNull(options.get(EXTERNAL_IDENTIFIER));
        verify(externalIdService).assignExternalId(STUDY, "POWERS", "healthCode");
    }
    
    @Test
    public void createParticipantWithInvalidParticipant() {
        // It doesn't get more invalid than this...
        StudyParticipant participant = new StudyParticipant.Builder().build();
        
        try {
            participantService.createParticipant(STUDY, participant);
            fail("Should have thrown exception");
        } catch(InvalidEntityException e) {
        }
        verifyNoMoreInteractions(accountDao);
        verifyNoMoreInteractions(optionsService);
        verifyNoMoreInteractions(externalIdService);
        verifyNoMoreInteractions(healthCodeService);
    }
    
    @Test
    public void createParticipantWithNoExternalIdValidation() {
        STUDY.setExternalIdValidationEnabled(false);
        doReturn("healthId").when(account).getHealthId();
        doReturn(healthId).when(healthCodeService).getMapping("healthId");
        doReturn("healthCode").when(healthId).getCode();
        when(accountDao.signUp(eq(STUDY), any(), eq(false))).thenReturn(account);
        
        participantService.createParticipant(STUDY, PARTICIPANT);

        verify(externalIdService).reserveExternalId(STUDY, "POWERS");
        // set externalId like any other option, we're not using externalIdService
        verify(optionsService).setAllOptions(eq(STUDY.getStudyIdentifier()), eq("healthCode"), optionsCaptor.capture());
        Map<ParticipantOption,String> options = optionsCaptor.getValue();
        assertEquals("POWERS", options.get(EXTERNAL_IDENTIFIER));
        verify(externalIdService).assignExternalId(STUDY, "POWERS", "healthCode");
    }
    
    @Test
    public void getPagedAccountSummaries() {
        participantService.getPagedAccountSummaries(STUDY, 1100, 50, "foo");
        
        verify(accountDao).getPagedAccountSummaries(STUDY, 1100, 50, "foo"); 
    }
    
    @Test(expected = NullPointerException.class)
    public void getPagedAccountSummariesWithBadStudy() {
        participantService.getPagedAccountSummaries(null, 0, 100, null);
    }
    
    @Test(expected = BadRequestException.class)
    public void getPagedAccountSummariesWithNegativeOffsetBy() {
        participantService.getPagedAccountSummaries(STUDY, -1, 100, null);
    }

    @Test(expected = BadRequestException.class)
    public void getPagedAccountSummariesWithNegativePageSize() {
        participantService.getPagedAccountSummaries(STUDY, 0, -100, null);
    }
    
    @Test
    public void getPagedAccountSummariesWithoutEmailFilterOK() {
        participantService.getPagedAccountSummaries(STUDY, 1100, 50, null);
        
        verify(accountDao).getPagedAccountSummaries(STUDY, 1100, 50, null); 
    }
    
    @Test(expected = BadRequestException.class)
    public void getPagedAccountSummariesWithTooLargePageSize() {
        participantService.getPagedAccountSummaries(STUDY, 0, 251, null);
    }
    
    @Test(expected = EntityNotFoundException.class)
    public void getParticipantEmailDoesNotExist() {
        when(accountDao.getAccount(STUDY, EMAIL)).thenReturn(null);
        
        participantService.getParticipant(STUDY, EMAIL);
    }
    
    @Test
    public void getStudyParticipant() {
        // A lot of mocks have to be set up first, this call aggregates almost everything we know about the user
        DateTime createdOn = DateTime.now();
        when(account.getHealthId()).thenReturn("healthId");
        when(account.getFirstName()).thenReturn("firstName");
        when(account.getLastName()).thenReturn("lastName");
        when(account.getEmail()).thenReturn(EMAIL);
        when(account.getStatus()).thenReturn(AccountStatus.DISABLED);
        when(account.getCreatedOn()).thenReturn(createdOn);
        when(account.getAttribute("attr2")).thenReturn("anAttribute2");
        
        mockGetAccount();
        
        List<Subpopulation> subpopulations = Lists.newArrayList();
        // Two subpopulations for mocking.
        Subpopulation subpop1 = Subpopulation.create();
        subpop1.setGuidString("guid1");
        subpopulations.add(subpop1);
        
        Subpopulation subpop2 = Subpopulation.create();
        subpop2.setGuidString("guid2");
        subpopulations.add(subpop2);
        when(subpopService.getSubpopulations(STUDY.getStudyIdentifier())).thenReturn(subpopulations);
        
        List<UserConsentHistory> histories1 = Lists.newArrayList();
        UserConsentHistory history1 = new UserConsentHistory.Builder()
                .withBirthdate("2002-02-02")
                .withConsentCreatedOn(1L)
                .withName("Test User")
                .withSubpopulationGuid(subpop1.getGuid())
                .withWithdrewOn(2L).build();
        histories1.add(history1);
        
        // Add another one, we don't need to test that it is the same.
        UserConsentHistory history2 = new UserConsentHistory.Builder().build();
        histories1.add(history2);
        
        List<UserConsentHistory> histories2 = Lists.newArrayList();
        
        when(consentService.getUserConsentHistory(STUDY, subpop1.getGuid(), "healthCode", EMAIL)).thenReturn(histories1);
        when(consentService.getUserConsentHistory(STUDY, subpop2.getGuid(), "healthCode", EMAIL)).thenReturn(histories2);
        
        when(lookup.getEnum(SHARING_SCOPE, SharingScope.class)).thenReturn(SharingScope.ALL_QUALIFIED_RESEARCHERS);
        when(lookup.getBoolean(EMAIL_NOTIFICATIONS)).thenReturn(true);
        when(lookup.getString(EXTERNAL_IDENTIFIER)).thenReturn("externalId");
        when(lookup.getStringSet(DATA_GROUPS)).thenReturn(TestUtils.newLinkedHashSet("group1","group2"));
        when(lookup.getOrderedStringSet(LANGUAGES)).thenReturn(TestUtils.newLinkedHashSet("fr","de"));
        when(optionsService.getOptions("healthCode")).thenReturn(lookup);
        
        // Get the participant
        StudyParticipant participant = participantService.getParticipant(STUDY, EMAIL);
        
        assertEquals("firstName", participant.getFirstName());
        assertEquals("lastName", participant.getLastName());
        assertTrue(participant.isNotifyByEmail());
        assertEquals(Sets.newHashSet("group1","group2"), participant.getDataGroups());
        assertEquals("externalId", participant.getExternalId());
        assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS, participant.getSharingScope());
        assertEquals("healthCode", participant.getHealthCode());
        assertEquals(EMAIL, participant.getEmail());
        assertEquals(AccountStatus.DISABLED, participant.getStatus());
        assertEquals(createdOn, participant.getCreatedOn());
        assertEquals(TestUtils.newLinkedHashSet("fr","de"), participant.getLanguages());
        
        assertNull(participant.getAttributes().get("attr1"));
        assertEquals("anAttribute2", participant.getAttributes().get("attr2"));
        
        List<UserConsentHistory> retrievedHistory1 = participant.getConsentHistories().get(subpop1.getGuidString());
        assertEquals(2, retrievedHistory1.size());
        assertEquals(history1, retrievedHistory1.get(0));
        assertEquals(history2, retrievedHistory1.get(1));
        
        List<UserConsentHistory> retrievedHistory2 = participant.getConsentHistories().get(subpop2.getGuidString());
        assertTrue(retrievedHistory2.isEmpty());
    }
    
    @Test
    public void getStudyParticipantWithoutHealthCode() {
        // A lot of mocks have to be set up first, this call aggregates almost everything we know about the user
        DateTime createdOn = DateTime.now();
        when(account.getHealthId()).thenReturn(null);
        when(account.getFirstName()).thenReturn("firstName");
        when(account.getLastName()).thenReturn("lastName");
        when(account.getEmail()).thenReturn(EMAIL);
        when(account.getStatus()).thenReturn(AccountStatus.DISABLED);
        when(account.getCreatedOn()).thenReturn(createdOn);
        when(account.getAttribute("attr2")).thenReturn("anAttribute2");
        
        when(accountDao.getAccount(STUDY, EMAIL)).thenReturn(account);
        when(healthId.getCode()).thenReturn(null);
        
        when(healthCodeService.createMapping(STUDY.getStudyIdentifier())).thenReturn(healthId);
        when(healthId.getId()).thenReturn("ABC");
        
        StudyParticipant participant = participantService.getParticipant(STUDY, EMAIL);
        
        assertEquals("firstName", participant.getFirstName());
        assertEquals("lastName", participant.getLastName());
        assertFalse(participant.isNotifyByEmail());
        assertTrue(participant.getDataGroups().isEmpty());
        assertNull(participant.getExternalId());
        assertNull(participant.getSharingScope());
        assertNull(participant.getHealthCode());
        assertEquals(EMAIL, participant.getEmail());
        assertEquals(AccountStatus.DISABLED, participant.getStatus());
        assertEquals(createdOn, participant.getCreatedOn());
        assertTrue(participant.getLanguages().isEmpty());
        assertNull(participant.getAttributes().get("attr1"));
        assertEquals("anAttribute2", participant.getAttributes().get("attr2"));
        assertTrue(participant.getConsentHistories().isEmpty());

        verify(accountDao).updateAccount(STUDY, account);
    }
    
    private void mockGetAccount() {
        when(accountDao.getAccount(STUDY, EMAIL)).thenReturn(account);
        when(account.getHealthId()).thenReturn("healthId");
        when(healthId.getCode()).thenReturn("healthCode");
        when(healthCodeService.getMapping("healthId")).thenReturn(healthId);
    }

    @Test(expected = EntityNotFoundException.class)
    public void signOutUserWhoDoesNotExist() {
        when(accountDao.getAccount(STUDY, EMAIL)).thenReturn(null);
        
        participantService.signUserOut(STUDY, EMAIL);
    }
    
    @Test
    public void signOutUser() {
        when(accountDao.getAccount(STUDY, EMAIL)).thenReturn(account);
        when(account.getId()).thenReturn("userId");
        
        participantService.signUserOut(STUDY, EMAIL);
        
        verify(accountDao).getAccount(STUDY, EMAIL);
        verify(cacheProvider).removeSessionByUserId("userId");
    }
    
    @Test
    public void updateParticipantWithExternalIdValidationAddingId() {
        STUDY.setExternalIdValidationEnabled(true);
        doReturn("healthId").when(account).getHealthId();
        doReturn(healthId).when(healthCodeService).getMapping("healthId");
        doReturn("healthCode").when(healthId).getCode();
        doReturn(account).when(accountDao).getAccount(STUDY, EMAIL);
        doReturn("id").when(account).getId();
        
        doReturn(lookup).when(optionsService).getOptions("healthCode");
        doReturn(null).when(lookup).getString(EXTERNAL_IDENTIFIER);
        
        participantService.updateParticipant(STUDY, EMAIL, PARTICIPANT);
        
        verify(optionsService).setAllOptions(eq(STUDY.getStudyIdentifier()), eq("healthCode"), optionsCaptor.capture());
        Map<ParticipantOption, String> options = optionsCaptor.getValue();
        assertEquals(SharingScope.ALL_QUALIFIED_RESEARCHERS.name(), options.get(SHARING_SCOPE));
        assertEquals("true", options.get(EMAIL_NOTIFICATIONS));
        assertEquals("group1,group2", options.get(DATA_GROUPS));
        assertEquals("de,fr", options.get(LANGUAGES));
        assertNull(options.get(EXTERNAL_IDENTIFIER));
        
        verify(accountDao).updateAccount(eq(STUDY), accountCaptor.capture());
        Account account = accountCaptor.getValue();
        verify(account).setFirstName("firstName");
        verify(account).setLastName("lastName");
        verify(account).setStatus(AccountStatus.DISABLED);
        verify(account).setAttribute("phone", "123456789");
        
        verify(cacheProvider).removeSessionByUserId("id");
    }
    
    @Test(expected = BadRequestException.class)
    public void updateParticipantWithExternalIdValidationChangingId() {
        STUDY.setExternalIdValidationEnabled(true);
        doReturn("healthId").when(account).getHealthId();
        doReturn(healthId).when(healthCodeService).getMapping("healthId");
        doReturn("healthCode").when(healthId).getCode();
        doReturn(account).when(accountDao).getAccount(STUDY, EMAIL);
        
        doReturn(lookup).when(optionsService).getOptions("healthCode");
        doReturn("BBB").when(lookup).getString(EXTERNAL_IDENTIFIER);
        
        participantService.updateParticipant(STUDY, EMAIL, PARTICIPANT);
    }

    @Test(expected = BadRequestException.class)
    public void updateParticipantWithExternalIdValidationRemovingId() {
        STUDY.setExternalIdValidationEnabled(true);
        doReturn("healthId").when(account).getHealthId();
        doReturn(healthId).when(healthCodeService).getMapping("healthId");
        doReturn("healthCode").when(healthId).getCode();
        doReturn(account).when(accountDao).getAccount(STUDY, EMAIL);
        
        doReturn(lookup).when(optionsService).getOptions("healthCode");
        doReturn("BBB").when(lookup).getString(EXTERNAL_IDENTIFIER);
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withFirstName(PARTICIPANT.getFirstName())
                .withLastName(PARTICIPANT.getLastName())
                .withSharingScope(PARTICIPANT.getSharingScope())
                .withNotifyByEmail(PARTICIPANT.isNotifyByEmail())
                .withDataGroups(PARTICIPANT.getDataGroups())
                .withAttributes(PARTICIPANT.getAttributes())
                .withLanguages(PARTICIPANT.getLanguages()).build();
        participantService.updateParticipant(STUDY, EMAIL, participant);
    }
    
    @Test
    public void updateParticipantWithExternalIdValidationNoIdChange() {
        STUDY.setExternalIdValidationEnabled(true);
        doReturn("healthId").when(account).getHealthId();
        doReturn(healthId).when(healthCodeService).getMapping("healthId");
        doReturn("healthCode").when(healthId).getCode();
        doReturn(account).when(accountDao).getAccount(STUDY, EMAIL);
        
        doReturn(lookup).when(optionsService).getOptions("healthCode");
        doReturn("POWERS").when(lookup).getString(EXTERNAL_IDENTIFIER);
        
        // This just succeeds because the IDs are the same, and we'll verify no attempt was made to update it.
        participantService.updateParticipant(STUDY, EMAIL, PARTICIPANT);
        
        verifyNoMoreInteractions(externalIdService);
    }
    
    @Test(expected = BadRequestException.class)
    public void updateParticipantWithExternalIdValidationIdMissing() {
        STUDY.setExternalIdValidationEnabled(true);
        doReturn("healthId").when(account).getHealthId();
        doReturn(healthId).when(healthCodeService).getMapping("healthId");
        doReturn("healthCode").when(healthId).getCode();
        doReturn(account).when(accountDao).getAccount(STUDY, EMAIL);
        
        doReturn(lookup).when(optionsService).getOptions("healthCode");
        doReturn(null).when(lookup).getString(EXTERNAL_IDENTIFIER);
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withFirstName(PARTICIPANT.getFirstName())
                .withLastName(PARTICIPANT.getLastName())
                .withSharingScope(PARTICIPANT.getSharingScope())
                .withNotifyByEmail(PARTICIPANT.isNotifyByEmail())
                .withDataGroups(PARTICIPANT.getDataGroups())
                .withAttributes(PARTICIPANT.getAttributes())
                .withLanguages(PARTICIPANT.getLanguages()).build();
        participantService.updateParticipant(STUDY, EMAIL, participant);
    }
    
    @Test(expected = InvalidEntityException.class)
    public void updateParticipantWithInvalidParticipant() {
        doReturn(account).when(accountDao).getAccount(STUDY, EMAIL);
        doReturn("healthId").when(account).getHealthId();
        doReturn(healthId).when(healthCodeService).getMapping("healthId");
        doReturn("healthCode").when(healthId).getCode();
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withDataGroups(Sets.newHashSet("bogusGroup"))
                .build();
        
        participantService.updateParticipant(STUDY, EMAIL, participant);
    }
    
    @Test
    public void updateParticipantWithNoAccount() {
        doThrow(new EntityNotFoundException(Account.class)).when(accountDao).getAccount(STUDY, EMAIL);
        try {
            participantService.updateParticipant(STUDY, EMAIL, PARTICIPANT);
            fail("Should have thrown exception.");
        } catch(EntityNotFoundException e) {
        }
        verify(accountDao, never()).updateAccount(eq(STUDY), any());
        verifyNoMoreInteractions(healthCodeService);
        verifyNoMoreInteractions(optionsService);
        verifyNoMoreInteractions(externalIdService);
    }
    
    @Test
    public void updateParticipantWithNoExternalIdValidation() {
        STUDY.setExternalIdValidationEnabled(false);
        doReturn("healthId").when(account).getHealthId();
        doReturn(healthId).when(healthCodeService).getMapping("healthId");
        doReturn("healthCode").when(healthId).getCode();
        doReturn(account).when(accountDao).getAccount(STUDY, EMAIL);
        
        participantService.updateParticipant(STUDY, EMAIL, PARTICIPANT);
        
        verifyNoMoreInteractions(externalIdService);
        verify(optionsService).setAllOptions(eq(STUDY.getStudyIdentifier()), eq("healthCode"), optionsCaptor.capture());
        Map<ParticipantOption, String> options = optionsCaptor.getValue();
        assertEquals("POWERS", options.get(EXTERNAL_IDENTIFIER));
    }
    
    @Test
    public void updateParticipantWithNoHealthCode() {
        STUDY.setExternalIdValidationEnabled(true);
        doReturn(null).when(healthCodeService).getMapping("healthId");
        doReturn(null).when(account).getHealthId();
        doReturn(account).when(accountDao).getAccount(STUDY, EMAIL);
        
        doReturn(healthId).when(healthCodeService).createMapping(STUDY.getStudyIdentifier());
        doReturn("healthId").when(healthId).getId();
        doReturn("healthCode").when(healthId).getCode();
        
        doReturn(lookup).when(optionsService).getOptions("healthCode");
        
        participantService.updateParticipant(STUDY, EMAIL, PARTICIPANT);
        
        verify(healthCodeService).createMapping(STUDY.getStudyIdentifier());
    }
    
    @Test
    public void createParticipantWithoutExternalIdAndNoValidation() {
        STUDY.setExternalIdValidationEnabled(false);
        doReturn(account).when(accountDao).signUp(eq(STUDY), any(), eq(false));
        doReturn("healthId").when(account).getHealthId();
        doReturn(healthId).when(healthCodeService).getMapping("healthId");
        doReturn("healthCode").when(healthId).getCode();
        
        // These are the minimal credentials and they should work.
        StudyParticipant participant = new StudyParticipant.Builder().withEmail(EMAIL).withPassword("P@ssword1")
                .build();
        
        participantService.createParticipant(STUDY, participant);
        verifyNoMoreInteractions(externalIdService); // no ID, no calls to this service
    }
}
