package org.sagebionetworks.bridge.validators;

import static java.lang.Boolean.TRUE;
import static java.lang.Boolean.FALSE;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.bridge.TestUtils.assertValidatorMessage;
import static org.sagebionetworks.bridge.TestConstants.PHONE;

import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.dynamodb.DynamoStudy;
import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.Phone;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.studies.PasswordPolicy;
import org.sagebionetworks.bridge.models.studies.Study;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

@RunWith(MockitoJUnitRunner.class)
public class StudyParticipantValidatorTest {
    
    private static final Set<String> STUDY_PROFILE_ATTRS = BridgeUtils.commaListToOrderedSet("attr1,attr2");
    private static final Set<String> STUDY_DATA_GROUPS = BridgeUtils.commaListToOrderedSet("group1,group2,bluebell");
    private static final Phone NEW_PHONE = new Phone("4082588569", "US");
    private static final String EMAIL = "email@email.com";
    private static final String NEW_EMAIL = "email2@email.com";
    
    private Study study;

    private StudyParticipantValidator validator;
    
    @Mock
    private Account account;
    
    @Before
    public void before() {
        study = new DynamoStudy();
        study.setIdentifier("test-study");
        study.setHealthCodeExportEnabled(true);
        study.setUserProfileAttributes(STUDY_PROFILE_ATTRS);
        study.setDataGroups(STUDY_DATA_GROUPS);
        study.setPasswordPolicy(PasswordPolicy.DEFAULT_PASSWORD_POLICY);
        study.getUserProfileAttributes().add("phone");
        study.setExternalIdValidationEnabled(false);
    }
    
    @Test
    public void validatesNew() throws Exception {
        validator = new StudyParticipantValidator(study, null);
        study.setExternalIdValidationEnabled(true);
        study.setExternalIdRequiredOnSignup(true);
        
        Map<String,String> attrs = Maps.newHashMap();
        attrs.put("badValue", "value");
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withDataGroups(Sets.newHashSet("badGroup"))
                .withAttributes(attrs)
                .withPassword("bad")
                .build();
        assertValidatorMessage(validator, participant, "StudyParticipant", "email or phone is required");
        assertValidatorMessage(validator, participant, "externalId", "is required");
        assertValidatorMessage(validator, participant, "dataGroups", "'badGroup' is not defined for study (use group1, group2, bluebell)");
        assertValidatorMessage(validator, participant, "attributes", "'badValue' is not defined for study (use attr1, attr2, phone)");
        assertValidatorMessage(validator, participant, "password", "must be at least 8 characters");
        assertValidatorMessage(validator, participant, "password", "must contain at least one number (0-9)");
        assertValidatorMessage(validator, participant, "password", "must contain at least one symbol ( !\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~ )");
        assertValidatorMessage(validator, participant, "password", "must contain at least one uppercase letter (A-Z)");
    }
    
    // Password, email address, and externalId (if being validated) cannot be updated, so these don't need to be validated.
    @Test
    public void validatesUpdate() {
        validator = new StudyParticipantValidator(study, account);
        
        Map<String,String> attrs = Maps.newHashMap();
        attrs.put("badValue", "value");
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withDataGroups(Sets.newHashSet("badGroup"))
                .withAttributes(attrs)
                .withPassword("bad")
                .build();
        
        try {
            Validate.entityThrowingException(validator, participant);
        } catch(InvalidEntityException e) {
            assertNull(e.getErrors().get("email"));
            assertNull(e.getErrors().get("externalId"));
            assertNull(e.getErrors().get("password"));
        }
        assertValidatorMessage(validator, participant, "dataGroups", "'badGroup' is not defined for study (use group1, group2, bluebell)");
        assertValidatorMessage(validator, participant, "attributes", "'badValue' is not defined for study (use attr1, attr2, phone)");
    }
    
    @Test
    public void validatesIdForNew() {
        // not new, this succeeds
        validator = new StudyParticipantValidator(study, null); 
        Validate.entityThrowingException(validator, withEmail("email@email.com"));
    }
    
    @Test(expected = InvalidEntityException.class)
    public void validatesIdForExisting() {
        // not new, this should fail, as there's no ID in participant.
        validator = new StudyParticipantValidator(study, account); 
        Validate.entityThrowingException(validator, withEmail("email@email.com"));
    }
    
    @Test
    public void validPasses() {
        validator = new StudyParticipantValidator(study, null);
        Validate.entityThrowingException(validator, withEmail("email@email.com"));
        Validate.entityThrowingException(validator, withDataGroup("bluebell"));
    }
    
    @Test
    public void emailOrPhoneRequired() {
        validator = new StudyParticipantValidator(study, null);
        assertValidatorMessage(validator, withEmail(null), "StudyParticipant", "email or phone is required");
    }
    
    @Test
    public void emptyStringPasswordRequired() {
        validator = new StudyParticipantValidator(study, null);
        assertValidatorMessage(validator, withPassword(""), "password", "is required");
    }
    
    @Test
    public void nullPasswordOK() {
        validator = new StudyParticipantValidator(study, null);
        Validate.entityThrowingException(validator, withPassword(null));
    }
    
    @Test
    public void validEmail() {
        validator = new StudyParticipantValidator(study, null);
        assertValidatorMessage(validator, withEmail("belgium"), "email", "does not appear to be an email address");
    }
    
    @Test
    public void minLength() {
        validator = new StudyParticipantValidator(study, null);
        assertValidatorMessage(validator, withPassword("a1A~"), "password", "must be at least 8 characters");
    }
    
    @Test
    public void numberRequired() {
        validator = new StudyParticipantValidator(study, null);
        assertValidatorMessage(validator, withPassword("aaaaaaaaA~"), "password", "must contain at least one number (0-9)");
    }
    
    @Test
    public void symbolRequired() {
        validator = new StudyParticipantValidator(study, null);
        assertValidatorMessage(validator, withPassword("aaaaaaaaA1"), "password", "must contain at least one symbol ( !\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~ )");
    }
    
    @Test
    public void lowerCaseRequired() {
        validator = new StudyParticipantValidator(study, null);
        assertValidatorMessage(validator, withPassword("AAAAA!A1"), "password", "must contain at least one lowercase letter (a-z)");
    }
    
    @Test
    public void upperCaseRequired() {
        validator = new StudyParticipantValidator(study, null);
        assertValidatorMessage(validator, withPassword("aaaaa!a1"), "password", "must contain at least one uppercase letter (A-Z)");
    }
    
    @Test
    public void validatesDataGroupsValidIfSupplied() {
        validator = new StudyParticipantValidator(study, null);
        assertValidatorMessage(validator, withDataGroup("squirrel"), "dataGroups", "'squirrel' is not defined for study (use group1, group2, bluebell)");
    }
    
    @Test
    public void validatePhoneRegionRequired() {
        validator = new StudyParticipantValidator(study, null);
        assertValidatorMessage(validator, withPhone("1234567890", null), "phone", "does not appear to be a phone number");
    }
    
    @Test
    public void validatePhoneRegionIsCode() {
        validator = new StudyParticipantValidator(study, null);
        assertValidatorMessage(validator, withPhone("1234567890", "esg"), "phone", "does not appear to be a phone number");
    }
    
    @Test
    public void validatePhoneRequired() {
        validator = new StudyParticipantValidator(study, null);
        assertValidatorMessage(validator, withPhone(null, "US"), "phone", "does not appear to be a phone number");
    }
    
    @Test
    public void validatePhonePattern() {
        validator = new StudyParticipantValidator(study, null);
        assertValidatorMessage(validator, withPhone("234567890", "US"), "phone", "does not appear to be a phone number");
    }
    
    @Test
    public void validatePhone() {
        validator = new StudyParticipantValidator(study, null);
        StudyParticipant participant = new StudyParticipant.Builder().withEmail("email@email.com")
                .withPassword("pAssword1@").withPhone(TestConstants.PHONE).build();
        Validate.entityThrowingException(validator, participant);
    }
    
    @Test
    public void validateTotallyWrongPhone() {
        validator = new StudyParticipantValidator(study, null);
        assertValidatorMessage(validator, withPhone("this isn't a phone number", "US"), "phone", "does not appear to be a phone number");
    }
    
    @Test
    public void noNullChange() {
        validator = new StudyParticipantValidator(study, account);
        assertFalse(validator.isUnchangedVerifiedValue(null, null, FALSE));
        assertTrue(validator.isUnchangedVerifiedValue(null, null, TRUE));
    }
    
    @Test
    public void nullChange() {
        validator = new StudyParticipantValidator(study, account);
        assertFalse(validator.isUnchangedVerifiedValue(null, NEW_EMAIL, TRUE));
        assertFalse(validator.isUnchangedVerifiedValue(EMAIL, null, TRUE));
        assertFalse(validator.isUnchangedVerifiedValue(null, NEW_EMAIL, FALSE));
        assertFalse(validator.isUnchangedVerifiedValue(EMAIL, null, FALSE));
    }
    
    @Test
    public void emailHasNotChanged() {
        validator = new StudyParticipantValidator(study, account);
        assertFalse(validator.isUnchangedVerifiedValue(EMAIL, EMAIL, FALSE));
        assertTrue(validator.isUnchangedVerifiedValue(EMAIL, EMAIL, TRUE));
    }
    
    @Test
    public void emailHasChanged() {
        validator = new StudyParticipantValidator(study, account);
        assertFalse(validator.isUnchangedVerifiedValue(EMAIL, NEW_EMAIL, FALSE));
        assertFalse(validator.isUnchangedVerifiedValue(EMAIL, NEW_EMAIL, TRUE));
    }
    
    @Test
    public void phoneHasNotChanged() {
        validator = new StudyParticipantValidator(study, account);
        assertFalse(validator.isUnchangedVerifiedValue(PHONE, PHONE, FALSE));
        assertTrue(validator.isUnchangedVerifiedValue(PHONE, PHONE, TRUE));
    }
    
    @Test
    public void phoneHasChanged() {
        validator = new StudyParticipantValidator(study, account);
        assertFalse(validator.isUnchangedVerifiedValue(PHONE, NEW_PHONE, FALSE));
        assertFalse(validator.isUnchangedVerifiedValue(PHONE, NEW_PHONE, TRUE));
    }
    
    @Test
    public void changingEmailOK() {
        mockEmailAndPhone(EMAIL, TRUE, PHONE, TRUE);
        validator = new StudyParticipantValidator(study, account);
        
        StudyParticipant participant = new StudyParticipant.Builder().withPhone(TestConstants.PHONE)
                .withEmail("email2@email2.com").withId("id").build();
        Validate.entityThrowingException(validator, participant);
    }
    
    @Test
    public void changingPhoneOK() {
        mockEmailAndPhone(EMAIL, TRUE, PHONE, TRUE);
        validator = new StudyParticipantValidator(study, account);
        
        StudyParticipant participant = new StudyParticipant.Builder().withEmail("email@email.com").withPhone(NEW_PHONE)
                .withId("id").build();
        Validate.entityThrowingException(validator, participant);
    }
    
    @Test(expected = InvalidEntityException.class)
    public void changeEmailWithoutPhoneNotOK() {
        mockEmailAndPhone(EMAIL, TRUE, null, null);
        validator = new StudyParticipantValidator(study, account);
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withId("id")
                .withEmail("email2@email2.com").build();
        Validate.entityThrowingException(validator, participant);
    }
    
    @Test(expected = InvalidEntityException.class)
    public void changeEmailWithUnverifiedPhoneNotOK() {
        mockEmailAndPhone(EMAIL, TRUE, PHONE, FALSE);
        validator = new StudyParticipantValidator(study, account);
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withId("id")
                .withEmail("email2@email2.com").build();
        Validate.entityThrowingException(validator, participant);
    }
    
    @Test(expected = InvalidEntityException.class)
    public void changePhoneWithoutEmailNotOK() {
        mockEmailAndPhone(null, null, PHONE, TRUE);
        validator = new StudyParticipantValidator(study, account);
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withId("id")
                .withPhone(NEW_PHONE).build();
        Validate.entityThrowingException(validator, participant);
    }
    
    @Test(expected = InvalidEntityException.class)
    public void changePhoneWithUnverifiedEmailNotOK() {
        mockEmailAndPhone(EMAIL, FALSE, PHONE, TRUE);
        validator = new StudyParticipantValidator(study, account);
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withId("id")
                .withPhone(NEW_PHONE).build();
        Validate.entityThrowingException(validator, participant);
    }
    
    @Test
    public void changingEmailAndPhoneNotOK() {
        mockEmailAndPhone(EMAIL, TRUE, PHONE, TRUE);
        validator = new StudyParticipantValidator(study, account);
        
        StudyParticipant participant = new StudyParticipant.Builder()
                .withId("id")
                .withEmail("email2@email2.com")
                .withPhone(NEW_PHONE).build();
        try {
            Validate.entityThrowingException(validator, participant);    
            fail("Should have thrown an exception");
        } catch(InvalidEntityException e) {
            assertTrue(e.getMessage().contains("cannot change email or phone when the other is unverified"));
        }
    }
    
    private void mockEmailAndPhone(String email, Boolean emailVerified, Phone phone, Boolean phoneVerified) {
        when(account.getEmail()).thenReturn(email);
        when(account.getEmailVerified()).thenReturn(emailVerified);
        when(account.getPhone()).thenReturn(phone);
        when(account.getPhoneVerified()).thenReturn(phoneVerified);
    }
    
    private StudyParticipant withPhone(String phone, String phoneRegion) {
        return new StudyParticipant.Builder().withPhone(new Phone(phone, phoneRegion)).build();
    }
    
    private StudyParticipant withPassword(String password) {
        return new StudyParticipant.Builder().withEmail("email@email.com").withPassword(password).build();
    }
    
    private StudyParticipant withEmail(String email) {
        return new StudyParticipant.Builder().withEmail(email).withPassword("aAz1%_aAz1%").build();
    }
    
    private StudyParticipant withDataGroup(String dataGroup) {
        return new StudyParticipant.Builder().withEmail("email@email.com").withPassword("aAz1%_aAz1%")
                .withDataGroups(Sets.newHashSet(dataGroup)).build();
    }
}
