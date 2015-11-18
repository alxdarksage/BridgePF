package org.sagebionetworks.bridge.validators;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.models.ClientInfo;
import org.sagebionetworks.bridge.models.accounts.User;
import org.sagebionetworks.bridge.models.schedules.ScheduleContext;

public class ScheduleContextValidatorTest {

    private ScheduleContextValidator validator = new ScheduleContextValidator();
    
    private User user;
    
    @Before
    public void before() {
        user = new User();
        user.setStudyKey("test-id");
        user.setHealthCode("AAA");
    }

    @Test
    public void validContext() {
        // The minimum you need to have a valid schedule context.
        ScheduleContext context = new ScheduleContext.Builder()
            .withClientInfo(ClientInfo.UNKNOWN_CLIENT)
            .withEndsOn(DateTime.now().plusDays(2))
            .withTimeZone(DateTimeZone.forOffsetHours(-3))
            .withUser(user)
            .build();
        
        Validate.nonEntityThrowingException(validator, context);
    }
    
    @Test
    public void clientInfoRequired() {
        ScheduleContext context = new ScheduleContext.Builder()
                .withEndsOn(DateTime.now().plusDays(2))
                .withTimeZone(DateTimeZone.forOffsetHours(-3))
                .withUser(user)
                .build();
        try {
            Validate.nonEntityThrowingException(validator, context);
            fail("Should have thrown exception");
        } catch(BadRequestException e) {
            assertTrue(e.getMessage().contains("clientInfo is required"));
        }
    }
    
    @Test
    public void studyIdentifierTimeZoneHealthCodeAndEndsOnAlwaysRequired() {
        ScheduleContext context = new ScheduleContext.Builder().build();
        try {
            Validate.nonEntityThrowingException(validator, context);
            fail("Should have thrown exception");
        } catch(BadRequestException e) {
            assertTrue(e.getMessage().contains("studyId is required"));
            assertTrue(e.getMessage().contains("offset must set a time zone offset"));
            assertTrue(e.getMessage().contains("healthCode is required"));
            assertTrue(e.getMessage().contains("endsOn is required"));
        }
    }

    @Test
    public void endsOnAfterNow() {
        ScheduleContext context = new ScheduleContext.Builder()
            .withTimeZone(DateTimeZone.UTC)
            .withEndsOn(DateTime.now().minusHours(1)).withUser(user).build();
        try {
            Validate.nonEntityThrowingException(validator, context);
            fail("Should have thrown exception");
        } catch(BadRequestException e) {
            assertTrue(e.getMessage().contains("endsOn must be after the time of the request"));
        }
    }
    
    @Test
    public void endsOnBeforeMaxNumDays() {
        // Setting this two days past the maximum. Will always fail.
        DateTime endsOn = DateTime.now().plusDays(ScheduleContextValidator.MAX_EXPIRES_ON_DAYS+2);
        ScheduleContext context = new ScheduleContext.Builder()
            .withTimeZone(DateTimeZone.UTC)
            .withEndsOn(endsOn).withUser(user).build();
        try {
            Validate.nonEntityThrowingException(validator, context);
            fail("Should have thrown exception");
        } catch(BadRequestException e) {
            assertTrue(e.getMessage().contains("endsOn must be 5 days or less"));
        }
    }
    
    
    
}
