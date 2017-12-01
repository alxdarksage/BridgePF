package org.sagebionetworks.bridge.models.oauth;

import java.util.Objects;

import org.joda.time.DateTime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class OAuthAccessToken {
    private final String vendorId;
    private final String accessToken;
    private final DateTime expiresOn;
    private final String providerUserId;
    
    @JsonCreator
    public OAuthAccessToken(@JsonProperty("vendorId") String vendorId, @JsonProperty("accessToken") String accessToken,
            @JsonProperty("expiresOn") DateTime expiresOn, @JsonProperty("providerUserId") String providerUserId) {
        this.vendorId = vendorId;
        this.accessToken = accessToken;
        this.expiresOn = expiresOn;
        this.providerUserId = providerUserId;
    }
    
    public String getVendorId() {
        return vendorId;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public DateTime getExpiresOn() {
        return expiresOn;
    }
    
    public String getProviderUserId() {
        return providerUserId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(vendorId, accessToken, expiresOn, providerUserId);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        OAuthAccessToken other = (OAuthAccessToken) obj;
        return Objects.equals(vendorId, other.vendorId)
                && Objects.equals(accessToken, other.accessToken)
                && Objects.equals(expiresOn, other.expiresOn)
                && Objects.equals(providerUserId, other.providerUserId);
    }

    @Override
    public String toString() {
        return "OAuthAccessToken [vendorId=" + vendorId + ", accessToken=" + accessToken + 
                ", expiresOn=" + expiresOn + ", providerUserId=" + providerUserId + "]";
    }
}