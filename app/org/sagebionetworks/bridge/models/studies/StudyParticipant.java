package org.sagebionetworks.bridge.models.studies;

import java.util.HashMap;
import java.util.Set;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.dao.ParticipantOption.SharingScope;
import org.sagebionetworks.bridge.models.accounts.UserProfile;

@SuppressWarnings("serial")
public final class StudyParticipant extends HashMap<String,String> {
    
    public String getEmpty(Object key) {
        String value = super.get(key);
        return (value == null) ? "" : value;
    }
    
    public String getSubpopulationNames() {
        return getEmpty(UserProfile.SUBPOPULATION_NAMES_FIELD);
    }
    public void setSubpopulationNames(String subpopNames) {
        put(UserProfile.SUBPOPULATION_NAMES_FIELD, subpopNames);
    }
    public String getFirstName() {
        return getEmpty(UserProfile.FIRST_NAME_FIELD);
    }
    public void setFirstName(String firstName) {
        put(UserProfile.FIRST_NAME_FIELD, firstName);
    }
    public String getLastName() {
        return getEmpty(UserProfile.LAST_NAME_FIELD);
    }
    public void setLastName(String lastName) {
        put(UserProfile.LAST_NAME_FIELD, lastName);
    }
    public String getEmail() {
        return getEmpty(UserProfile.EMAIL_FIELD);
    }
    public void setEmail(String email) {
        put(UserProfile.EMAIL_FIELD, email);
    }
    public String getExternalId() {
        return getEmpty(UserProfile.EXTERNAL_ID_FIELD);
    }
    public void setExternalId(String externalId) {
        put(UserProfile.EXTERNAL_ID_FIELD, externalId);
    }
    public SharingScope getSharingScope() {
        String name = get(UserProfile.SHARING_SCOPE_FIELD);
        return (name == null) ? null : SharingScope.valueOf(name);
    }
    public void setSharingScope(SharingScope scope) {
        if (scope != null) {
            put(UserProfile.SHARING_SCOPE_FIELD, scope.name());    
        }
    }
    public Boolean getNotifyByEmail() {
        String emptyString = get(UserProfile.NOTIFY_BY_EMAIL_FIELD);
        return (emptyString == null) ? null : Boolean.valueOf(emptyString);
    }
    public void setNotifyByEmail(Boolean notifyByEmail) {
        if (notifyByEmail != null) {
            put(UserProfile.NOTIFY_BY_EMAIL_FIELD, notifyByEmail.toString());    
        }
    }
    public String getDataGroups() {
        return getEmpty(UserProfile.DATA_GROUPS_FIELD);
    }
    public void setDataGroups(Set<String> dataGroups) {
        if (dataGroups != null) {
            put(UserProfile.DATA_GROUPS_FIELD, BridgeUtils.setToCommaList(dataGroups));    
        }
    }
    public String getHealthCode() {
        return getEmpty(UserProfile.HEALTH_CODE_FIELD);
    }
    public void setHealthCode(String healthCode) {
        put(UserProfile.HEALTH_CODE_FIELD, healthCode);
    }
}
