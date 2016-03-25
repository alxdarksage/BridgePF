package org.sagebionetworks.bridge.models.accounts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.accounts.UserProfile;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Sets;

public class UserProfileTest {

    @Test
    public void canSerialize() {
        UserProfile profile = new UserProfile();
        profile.setFirstName("firstName");
        profile.setLastName("lastName");
        profile.setEmail("email@email.com");
        profile.setStatus(AccountStatus.UNVERIFIED);
        profile.setAttribute("foo", "bar");
        
        JsonNode node = BridgeObjectMapper.get().valueToTree(profile);
        // Attribute is included as part of profile object
        assertEquals("bar", node.get("foo").asText());
        assertEquals("firstName", node.get("firstName").asText());
        assertEquals("lastName", node.get("lastName").asText());
        assertEquals("email@email.com", node.get("email").asText());
        assertEquals("unverified", node.get("status").asText());
        assertEquals("UserProfile", node.get("type").asText());
        
        UserProfile deserProfile = UserProfile.fromJson(Sets.newHashSet("foo"), node);
        assertEquals("bar", deserProfile.getAttribute("foo"));
        assertEquals("firstName", deserProfile .getFirstName());
        assertEquals("lastName", deserProfile.getLastName());
        assertEquals(AccountStatus.UNVERIFIED, deserProfile.getStatus());
        // Users are never allowed to submit JSON that changes their email:
        assertNull(deserProfile.getEmail());
    }

    @Test
    public void cannotSetReservedWordAttribute() {
        String reservedField = UserProfile.RESERVED_ATTR_NAMES.iterator().next();
        
        UserProfile profile = new UserProfile();
        profile.setAttribute(reservedField, "bar");
        
        assertNull(profile.getAttribute(reservedField));
    }
    
}
