package org.sagebionetworks.bridge.models;

import java.util.List;
import java.util.Map;

import org.joda.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class DateRangeResourceList<T> extends ResourceList<T> {
    
    private final LocalDate startDate;
    private final LocalDate endDate;

    @JsonCreator
    public DateRangeResourceList(@JsonProperty("items") List<T> items, @JsonProperty("startDate") LocalDate startDate,
            @JsonProperty("endDate") LocalDate endDate) {
        super(items);
        this.startDate = startDate;
        this.endDate = endDate;
    }
    public LocalDate getStartDate() {
        return startDate;
    }
    public LocalDate getEndDate() {
        return endDate;
    }
    @Override
    public Map<String, Object> getRequestParams() {
        Map<String, Object> map = super.getRequestParams();
        map.put("startDate", startDate);
        map.put("endDate", endDate);
        return map;
    }
}
