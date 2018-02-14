package org.sagebionetworks.bridge.services;

import static org.sagebionetworks.bridge.TestConstants.TEST_STUDY;
import static org.sagebionetworks.bridge.dao.ParticipantOption.EXTERNAL_IDENTIFIER;
import static org.sagebionetworks.bridge.dao.ParticipantOption.DATA_GROUPS;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.dao.FPHSExternalIdentifierDao;
import org.sagebionetworks.bridge.dynamodb.DynamoFPHSExternalIdentifier;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifier;
import org.sagebionetworks.bridge.models.accounts.FPHSExternalIdentifier;
import org.sagebionetworks.bridge.models.accounts.ParticipantOptionsLookup;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class FPHSServiceTest {

    private FPHSService service;
    private FPHSExternalIdentifierDao dao;
    private ParticipantOptionsService optionsService;
    private ExternalIdentifier externalId;
    
    @Before
    public void before() {
        externalId = ExternalIdentifier.create(TEST_STUDY, "gar");
        service = new FPHSService();
        dao = mock(FPHSExternalIdentifierDao.class);
        optionsService = mock(ParticipantOptionsService.class);
        
        service.setFPHSExternalIdentifierDao(dao); 
        service.setParticipantOptionsService(optionsService);
    }
    
    @Test(expected = InvalidEntityException.class)
    public void validateIdThrowsException() throws Exception {
        service.verifyExternalIdentifier(ExternalIdentifier.create(TEST_STUDY, ""));
    }
    
    @Test
    public void verifyExternalIdentifierSucceeds() throws Exception {
        service.verifyExternalIdentifier(externalId);
        verify(dao).verifyExternalId(externalId);
    }
    
    @Test(expected = EntityNotFoundException.class)
    public void verifyExternalIdentifierFailsOnNotFound() throws Exception {
        doThrow(new EntityNotFoundException(ExternalIdentifier.class)).when(dao).verifyExternalId(externalId);
        
        service.verifyExternalIdentifier(externalId);
    }
    
    @Test(expected = InvalidEntityException.class)
    public void registerIdThrowsException() throws Exception {
        service.registerExternalIdentifier(TEST_STUDY, "BBB", ExternalIdentifier.create(TEST_STUDY, null));
    }
    
    @Test
    public void registerExternalIdentifier() throws Exception {
        doReturn(new ParticipantOptionsLookup(Maps.newHashMap())).when(optionsService).getOptions(TEST_STUDY, "BBB");
        
        service.registerExternalIdentifier(TEST_STUDY, "BBB", externalId);
        verify(dao).registerExternalId(externalId);
        verify(optionsService).setString(TEST_STUDY, "BBB", EXTERNAL_IDENTIFIER, externalId.getIdentifier());
    }
    
    @Test
    public void failureOfDaoDoeNotSetExternalId() throws Exception {
        // Mock this, throw exception afterward
        doReturn(new ParticipantOptionsLookup(Maps.newHashMap())).when(optionsService).getOptions(TEST_STUDY, "BBB");
        doThrow(new EntityNotFoundException(ExternalIdentifier.class, "Not found")).when(dao).registerExternalId(externalId);
        try {
            service.registerExternalIdentifier(TEST_STUDY, "BBB", externalId);
            fail("Exception should have been thrown");
        } catch(EntityNotFoundException e) {
            verify(dao).verifyExternalId(externalId);
            verify(dao).registerExternalId(externalId);
            verifyNoMoreInteractions(dao);
            
            // Options service will be called. The id may not be registered, if so, the call
            // can be called again, the service calls are idempotent. Or else it actually was 
            // recorded, in which case it's also fine, despite the exception.
            verify(optionsService).getOptions(TEST_STUDY, "BBB");
            verify(optionsService).setString(TEST_STUDY, "BBB", EXTERNAL_IDENTIFIER, externalId.getIdentifier());
            verify(optionsService).setStringSet(TEST_STUDY, "BBB", DATA_GROUPS, Sets.newHashSet("football_player"));
            verifyNoMoreInteractions(optionsService);
        }
    }
    
    @Test
    public void failureToSetExternalIdRollsBackRegistration() throws Exception {
        doThrow(new RuntimeException()).when(dao).verifyExternalId(any());
        try {
            service.registerExternalIdentifier(TEST_STUDY, "BBB", externalId);
            fail("Exception should have been thrown");
        } catch(RuntimeException e) {
            verify(dao).verifyExternalId(externalId);
            verifyNoMoreInteractions(dao);
            verifyNoMoreInteractions(optionsService);
        }
    }
    
    @Test
    public void getExternalIdentifiers() throws Exception {
        List<FPHSExternalIdentifier> externalIds = Lists.newArrayList(
                new DynamoFPHSExternalIdentifier("foo"), new DynamoFPHSExternalIdentifier("bar"));
        when(dao.getExternalIds()).thenReturn(externalIds);
        
        List<FPHSExternalIdentifier> identifiers = service.getExternalIdentifiers();
        
        assertEquals(externalIds, identifiers);
        verify(dao).getExternalIds();
    }
    
    @Test
    public void addExternalIdentifiers() throws Exception {
        List<FPHSExternalIdentifier> identifiers = Lists.newArrayList(FPHSExternalIdentifier.create("AAA"),
                FPHSExternalIdentifier.create("BBB"), FPHSExternalIdentifier.create("CCC"));
        
        service.addExternalIdentifiers(identifiers);
        verify(dao).addExternalIds(identifiers);
    }
    
}
