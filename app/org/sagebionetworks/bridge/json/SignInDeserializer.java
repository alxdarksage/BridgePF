package org.sagebionetworks.bridge.json;

import java.io.IOException;

import org.apache.commons.lang3.StringUtils;

import org.sagebionetworks.bridge.models.accounts.SignIn;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * For backwards compatibility, this deserializer will look for an email address in the "username" 
 * field as well as the "email" field, so this object must be deserialized manually.
 */
public class SignInDeserializer extends JsonDeserializer<SignIn> {

    @Override
    public SignIn deserialize(JsonParser parser, DeserializationContext context)
            throws IOException, JsonProcessingException {
        JsonNode node = parser.getCodec().readTree(parser);
        String username = JsonUtils.asText(node, "username");
        String email = JsonUtils.asText(node, "email");
        String phone = JsonUtils.asText(node, "phone");
        String password = JsonUtils.asText(node, "password");
        String studyId = JsonUtils.asText(node, "study");
        String token = JsonUtils.asText(node, "token");
        String reauthToken = JsonUtils.asText(node, "reauthToken");
        
        String accountIdentifier = (StringUtils.isNotBlank(username)) ? username : email;
        
        return new SignIn.Builder().withStudy(studyId).withEmail(accountIdentifier).withPhone(phone)
                .withPassword(password).withToken(token).withReauthToken(reauthToken).build();
    }

}
