package org.sagebionetworks.bridge.play.controllers;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import play.mvc.Result;

import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.models.DateRange;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.studies.StudyIdentifierImpl;
import org.sagebionetworks.bridge.services.UserDataDownloadService;

@RunWith(MockitoJUnitRunner.class)
public class UserDataDownloadControllerTest {
    private static final String START_DATE = "2015-08-15";
    private static final String END_DATE = "2015-08-19";
    private static final StudyIdentifier STUDY_ID = new StudyIdentifierImpl("test-study");
    private static final String USER_ID = "test-user-id";
    private static final String EMAIL = "email@email.com";

    @Mock
    UserSession mockSession;
    
    @Mock
    UserDataDownloadService mockService;
    
    @Spy
    UserDataDownloadController controller;
    
    @Captor
    ArgumentCaptor<DateRange> dateRangeCaptor;
    
    @Before
    public void before() throws Exception {
        controller.setUserDataDownloadService(mockService);
        doReturn(mockSession).when(controller).getAuthenticatedAndConsentedSession();
        doReturn(STUDY_ID).when(mockSession).getStudyIdentifier();
    }
    
    @Test
    public void testWithEmail() throws Exception {
        StudyParticipant participant = new StudyParticipant.Builder().withId(USER_ID).withEmail(EMAIL)
                .withEmailVerified(Boolean.TRUE).build();
        doReturn(participant).when(mockSession).getParticipant();
        mockWithJson();

        // execute and validate
        Result result = controller.requestUserData();
        TestUtils.assertResult(result, 202);

        verify(mockService).requestUserData(eq(STUDY_ID), eq(USER_ID), dateRangeCaptor.capture());
        
        // validate args sent to mock service
        DateRange dateRange = dateRangeCaptor.getValue();
        assertEquals("2015-08-15", dateRange.getStartDate().toString());
        assertEquals("2015-08-19", dateRange.getEndDate().toString());
    }

    @Test
    public void testWithPhone() throws Exception {
        StudyParticipant participant = new StudyParticipant.Builder().withId(USER_ID)
                .withPhone(TestConstants.PHONE).withPhoneVerified(Boolean.TRUE).build();
        doReturn(participant).when(mockSession).getParticipant();
        mockWithJson();

        // execute and validate
        Result result = controller.requestUserData();
        TestUtils.assertResult(result, 202);

        verify(mockService).requestUserData(eq(STUDY_ID), eq(USER_ID), dateRangeCaptor.capture());
        
        // validate args sent to mock service
        DateRange dateRange = dateRangeCaptor.getValue();
        assertEquals("2015-08-15", dateRange.getStartDate().toString());
        assertEquals("2015-08-19", dateRange.getEndDate().toString());
    }
    
    @Test(expected = BadRequestException.class)
    public void throwExceptionIfAccountHasNoEmail() throws Exception {
        StudyParticipant participant = new StudyParticipant.Builder().withId(USER_ID).build();
        doReturn(participant).when(mockSession).getParticipant();
        mockWithJson();

        controller.requestUserData();
    }
    
    @Test(expected = BadRequestException.class)
    public void throwExceptionIfAccountEmailUnverified() throws Exception {
        StudyParticipant participant = new StudyParticipant.Builder().withId(USER_ID)
                .withEmail(EMAIL).withEmailVerified(Boolean.FALSE).build();
        doReturn(participant).when(mockSession).getParticipant();
        mockWithJson();

        controller.requestUserData();
    }

    @Test(expected = BadRequestException.class)
    public void throwExceptionIfAccountHasNoPhone() throws Exception {
        StudyParticipant participant = new StudyParticipant.Builder().withId(USER_ID)
                .withPhoneVerified(Boolean.TRUE).build();
        doReturn(participant).when(mockSession).getParticipant();
        mockWithJson();

        controller.requestUserData();
    }
    
    @Test(expected = BadRequestException.class)
    public void throwExceptionIfAccountPhoneUnverified() throws Exception {
        StudyParticipant participant = new StudyParticipant.Builder().withId(USER_ID)
                .withPhone(TestConstants.PHONE).withPhoneVerified(Boolean.FALSE).build();
        doReturn(participant).when(mockSession).getParticipant();
        mockWithJson();

        controller.requestUserData();
    }
    
    private void mockWithJson() throws Exception {
        // This isn't in the before(), because we don't want to mock the JSON body for the query params test.
        String dateRangeJsonText = "{\n" +
                "   \"startDate\":\"" + START_DATE + "\",\n" +
                "   \"endDate\":\"" + END_DATE + "\"\n" +
                "}";
        TestUtils.mockPlay().withJsonBody(dateRangeJsonText).mock();
    }
}
