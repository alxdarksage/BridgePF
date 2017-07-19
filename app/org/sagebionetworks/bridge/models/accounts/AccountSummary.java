package org.sagebionetworks.bridge.models.accounts;

import java.util.Objects;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import org.sagebionetworks.bridge.models.studies.StudyIdentifier;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class AccountSummary {
    private final String firstName;
    private final String lastName;
    private final String email;
    private final String id;
    private final DateTime createdOn;
    private final AccountStatus status;
    private final StudyIdentifier studyIdentifier;
    
    @JsonCreator
    public AccountSummary(@JsonProperty("firstName") String firstName, @JsonProperty("lastName") String lastName,
            @JsonProperty("email") String email, @JsonProperty("id") String id,
            @JsonProperty("createdOn") DateTime createdOn, @JsonProperty("status") AccountStatus status,
            @JsonProperty("studyIdentifier") StudyIdentifier studyIdentifier) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.id = id;
        this.createdOn = (createdOn == null) ? null : createdOn.withZone(DateTimeZone.UTC);
        this.status = status;
        this.studyIdentifier = studyIdentifier;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }
    
    public String getEmail() {
        return email;
    }
    
    public String getId() {
        return id;
    }

    public DateTime getCreatedOn() {
        return createdOn;
    }

    public AccountStatus getStatus() {
        return status;
    }
    
    public StudyIdentifier getStudyIdentifier() {
        return studyIdentifier;
    }

    @Override
    public int hashCode() {
        return Objects.hash(firstName, lastName, email, id, createdOn, status, studyIdentifier);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        AccountSummary other = (AccountSummary) obj;
        return Objects.equals(firstName, other.firstName) && Objects.equals(lastName, other.lastName)
                && Objects.equals(email, other.email) && Objects.equals(createdOn, other.createdOn)
                && Objects.equals(status, other.status) && Objects.equals(id, other.id)
                && Objects.equals(studyIdentifier, other.studyIdentifier);
    }
    
    // no toString() method as the information is sensitive.
}
