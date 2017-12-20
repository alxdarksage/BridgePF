package org.sagebionetworks.bridge.validators;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.util.Set;

import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.Phone;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.studies.PasswordPolicy;
import org.sagebionetworks.bridge.models.studies.Study;

public class StudyParticipantValidator implements Validator {

    private static final EmailValidator EMAIL_VALIDATOR = EmailValidator.getInstance();
    private final Study study;
    private final Account account;
    private final boolean isNew;
    
    public StudyParticipantValidator(Study study, Account account) {
        this.study = study;
        this.account = account;
        this.isNew = (account == null);
    }
    
    @Override
    public boolean supports(Class<?> clazz) {
        return StudyParticipant.class.isAssignableFrom(clazz);
    }

    @Override
    public void validate(Object object, Errors errors) {
        StudyParticipant participant = (StudyParticipant)object;
        
        if (isNew) {
            Phone phone = participant.getPhone();
            String email = participant.getEmail();
            if (isBlank(email) && phone == null) {
                errors.reject("email or phone is required");
            }
            // If provided, phone must be valid
            if (phone != null && !Phone.isValid(phone)) {
                errors.rejectValue("phone", "does not appear to be a phone number");
            }
            // If provided, email must be valid
            if (isNotBlank(email) && !EMAIL_VALIDATOR.isValid(email)) {
                errors.rejectValue("email", "does not appear to be an email address");
            }
            if (study.isExternalIdRequiredOnSignup() && isBlank(participant.getExternalId())) {
                errors.rejectValue("externalId", "is required");
            }
            // Password is optional, but validation is applied if supplied, any time it is 
            // supplied (such as in the password reset workflow).
            String password = participant.getPassword();
            if (password != null) {
                PasswordPolicy passwordPolicy = study.getPasswordPolicy();
                ValidatorUtils.validatePassword(errors, passwordPolicy, password);
            }
        } else {
            if (isBlank(participant.getId())) {
                errors.rejectValue("id", "is required");
            }
            // You cannot nullify a value. I'm not sure what could break if you do this.
            if (participant.getEmail() == null && account.getEmail() != null) {
                errors.rejectValue("email", "cannot be deleted");
            }
            if (participant.getPhone() == null && account.getPhone() != null) {
                errors.rejectValue("phone", "cannot be deleted");
            }
            if (!errors.hasErrors()) {
                // Furthermore, you can only change email or phone if the other value remains unchanged, and 
                // it is already verified. This is to prevent a user from locking themselves out of the account.
                boolean emailChanged = !isUnchangedVerifiedValue(account.getEmail(), participant.getEmail(),
                        account.getEmailVerified());
                boolean phoneChanged = !isUnchangedVerifiedValue(account.getPhone(), participant.getPhone(),
                        account.getPhoneVerified());
                if (emailChanged && phoneChanged) {
                    errors.reject("cannot change email or phone when the other is unverified (or both at the same time)");
                }
            }
        }
        
        // if external ID validation is enabled, it's not covered by the validator.
        for (String dataGroup : participant.getDataGroups()) {
            if (!study.getDataGroups().contains(dataGroup)) {
                errors.rejectValue("dataGroups", messageForSet(study.getDataGroups(), dataGroup));
            }
        }
        for (String attributeName : participant.getAttributes().keySet()) {
            if (!study.getUserProfileAttributes().contains(attributeName)) {
                errors.rejectValue("attributes", messageForSet(study.getUserProfileAttributes(), attributeName));
            }
        }
    }
    
    <T> boolean isUnchangedVerifiedValue(T original, T changed, Boolean verified) {
        return !BridgeUtils.valueChanged(original, changed) && verified == Boolean.TRUE;
    }
    
    private String messageForSet(Set<String> set, String fieldName) {
        return String.format("'%s' is not defined for study (use %s)", 
                fieldName, BridgeUtils.COMMA_SPACE_JOINER.join(set));
    }
}
