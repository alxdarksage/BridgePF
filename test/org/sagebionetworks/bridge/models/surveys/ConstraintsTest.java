package org.sagebionetworks.bridge.models.surveys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.joda.time.DateTime;
import org.junit.Test;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.json.JsonUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ConstraintsTest {

    /**
     * It is possible to configure the constraints sub-typing information such that dataType 
     * is serialized twice, which is invalid. Test here that this no longer happens.
     * @throws Exception
     */
    @Test
    public void constraintsDoNotSerializeDataTypeTwice() throws Exception {
        DateTimeConstraints constraints = new DateTimeConstraints();
        constraints.setEarliestValue(DateTime.parse("2015-01-01T10:10:10-07:00"));
        constraints.setLatestValue(DateTime.parse("2015-12-31T10:10:10-07:00"));

        JsonNode node = BridgeObjectMapper.get().valueToTree(constraints);
        assertEquals("datetime", node.get("dataType").asText());
        assertEquals("DateTimeConstraints", node.get("type").asText());
        ((ObjectNode)node).remove("type");
        
        // embed constraints in object to test deserialization
        ObjectNode objectNode = JsonNodeFactory.instance.objectNode();
        objectNode.set("constraints", node);
        Constraints deser = JsonUtils.asConstraints(objectNode, "constraints");
        
        // Even without the type property, this will deserialize because it is based 
        // on dataType (which is required, and required by validation).
        assertTrue(deser instanceof DateTimeConstraints);
        assertEquals(DataType.DATETIME, deser.getDataType());
    }
}
