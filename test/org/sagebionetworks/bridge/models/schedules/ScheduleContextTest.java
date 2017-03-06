package org.sagebionetworks.bridge.models.schedules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;

import java.util.HashMap;
import java.util.Map;

import nl.jqno.equalsverifier.EqualsVerifier;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;

import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.models.ClientInfo;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.studies.StudyIdentifierImpl;

public class ScheduleContextTest {

    @Test
    public void equalsHashCode() {
        EqualsVerifier.forClass(ScheduleContext.class).allFieldsShouldBeUsed().verify();
    }
    
    @Test
    public void quietlyReturnsFalseForEvents() {
        ScheduleContext context = new ScheduleContext.Builder().withRequestTimeZone(DateTimeZone.UTC)
                .withStudyIdentifier(TestConstants.TEST_STUDY).build();
        assertNull(context.getEvent("enrollment"));
        assertFalse(context.hasEvents());
        
        context = new ScheduleContext.Builder().withRequestTimeZone(DateTimeZone.UTC)
                .withStudyIdentifier(TestConstants.TEST_STUDY)
                .withEvents(new HashMap<String, DateTime>()).build();
        assertNull(context.getEvent("enrollment"));
        assertFalse(context.hasEvents());
    }
    
    @Test(expected = NullPointerException.class)
    public void requiresStudyId() {
        new ScheduleContext.Builder().build();
    }
    
    @Test
    public void defaultsTimeZoneMinimumAndClientInfo() {
        DateTimeZone PST = DateTimeZone.forOffsetHours(-7);
        ScheduleContext context = new ScheduleContext.Builder()
                .withInitialTimeZone(PST)
                .withRequestTimeZone(DateTimeZone.UTC)
                .withStudyIdentifier(TestConstants.TEST_STUDY).build();
        
        assertEquals(ClientInfo.UNKNOWN_CLIENT, context.getCriteriaContext().getClientInfo());
        assertNotNull(context.getNow());
        assertEquals(0, context.getMinimumPerSchedule());
    }
    
    @Test
    public void builderWorks() {
        ClientInfo clientInfo = ClientInfo.fromUserAgentCache("app/5");
        StudyIdentifier studyId = new StudyIdentifierImpl("study-key");
        DateTimeZone PST = DateTimeZone.forOffsetHours(-7);
        DateTimeZone EST = DateTimeZone.forOffsetHours(-3);
        DateTime endsOn = DateTime.now(EST);
        DateTime now = DateTime.now(EST);
        
        Map<String,DateTime> events = new HashMap<>();
        events.put("enrollment", DateTime.now());
        
        // All the individual fields work
        ScheduleContext context = new ScheduleContext.Builder()
                .withClientInfo(clientInfo)
                .withStudyIdentifier(studyId)
                .withInitialTimeZone(PST)
                .withRequestTimeZone(EST)
                .withEndsOn(endsOn)
                .withMinimumPerSchedule(3)
                .withEvents(events)
                .withHealthCode("healthCode")
                .withUserDataGroups(TestConstants.USER_DATA_GROUPS)
                .withNow(now).build();
        assertEquals(studyId, context.getCriteriaContext().getStudyIdentifier());
        assertEquals(clientInfo, context.getCriteriaContext().getClientInfo());
        assertEquals(PST, context.getInitialTimeZone());
        assertEquals(EST, context.getRequestTimeZone());
        assertEquals(endsOn, context.getEndsOn());
        assertEquals(events.get("enrollment"), context.getEvent("enrollment"));
        assertEquals(3, context.getMinimumPerSchedule());
        assertEquals("healthCode", context.getCriteriaContext().getHealthCode());
        assertEquals(TestConstants.USER_DATA_GROUPS, context.getCriteriaContext().getUserDataGroups());
        assertEquals(now, context.getNow());

        // and the other studyId setter
        context = new ScheduleContext.Builder().withRequestTimeZone(PST).withStudyIdentifier("study-key").build();
        assertEquals(studyId, context.getCriteriaContext().getStudyIdentifier());
    }
    
    @Test
    public void eventTimesAreForcedToUTC() {
        ScheduleContext context = new ScheduleContext.Builder()
                .withAccountCreatedOn(DateTime.parse("2010-10-10T10:10:10.010+03:00"))
                .withRequestTimeZone(DateTimeZone.forOffsetHours(3))
                .withStudyIdentifier("study-Id")
                .build();
        assertEquals("2010-10-10T07:10:10.010Z", context.getAccountCreatedOn().toString());
        
        ScheduleContext context2 = new ScheduleContext.Builder().withContext(context).build();
        assertEquals("2010-10-10T07:10:10.010Z", context2.getAccountCreatedOn().toString());
    }
    
    @Test
    public void daysAheadConvertedToEndsOn() {
        DateTimeZone MSK = DateTimeZone.forOffsetHours(3);
        DateTime now = DateTime.parse("2010-10-10T10:10:10.010+03:00");

        ScheduleContext context = new ScheduleContext.Builder()
                .withRequestTimeZone(MSK)
                .withStudyIdentifier(TestConstants.TEST_STUDY)
                .withDaysAhead(3)
                .withNow(now).build();
        
        assertEquals("2010-10-13T23:59:59.999+03:00", context.getEndsOn().toString());
    }
    
    @Test
    public void requestTimeZoneNull() {
        ScheduleContext context = new ScheduleContext.Builder().withStudyIdentifier(TestConstants.TEST_STUDY).build();
        assertNull(context.getRequestTimeZone());
    }
    
    @Test
    public void initialTimeZoneFromRequestZone() {
        DateTimeZone PST = DateTimeZone.forOffsetHours(-7);
        
        ScheduleContext context = new ScheduleContext.Builder().withStudyIdentifier(TestConstants.TEST_STUDY)
                .withRequestTimeZone(PST).build();
        assertEquals(PST, context.getRequestTimeZone());
        assertEquals(PST, context.getInitialTimeZone());
    }
}
