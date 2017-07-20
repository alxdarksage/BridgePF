package org.sagebionetworks.bridge.models;

import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;

import org.sagebionetworks.bridge.json.DateTimeSerializer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

public class DateTimeRangeResourceList<T> extends ResourceList<T> {
    private final DateTime startTime;
    private final DateTime endTime;

    @JsonCreator
    public DateTimeRangeResourceList(@JsonProperty("items") List<T> items,
            @JsonProperty("startTime") DateTime startTime, @JsonProperty("endTime") DateTime endTime) {
        super(items);
        this.startTime = startTime;
        this.endTime = endTime;
    }
    @JsonSerialize(using = DateTimeSerializer.class)
    public DateTime getStartTime() {
        return startTime;
    }
    @JsonSerialize(using = DateTimeSerializer.class)
    public DateTime getEndTime() {
        return endTime;
    }
    @Override
    public Map<String, Object> getRequestParams() {
        Map<String, Object> map = super.getRequestParams();
        if (startTime != null) {
            map.put("startTime", startTime.toString());    
        }
        if (endTime != null) {
            map.put("endTime", endTime.toString());    
        }
        return map;
    }
}
