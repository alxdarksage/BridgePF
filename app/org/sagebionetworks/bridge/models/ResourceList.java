package org.sagebionetworks.bridge.models;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

public class ResourceList<T> {
    
    private final Map<String,String> filters = Maps.newHashMap();
    
    private final List<T> items;

    @JsonCreator
    public ResourceList(@JsonProperty("items") List<T> items) {
        this.items = items;
    }
    public List<T> getItems() {
        return items;
    }
    public ResourceList<T> withFilter(String key, String value) {
        if (isNotBlank(key) && isNotBlank(value)) {
            filters.put(key, value);
        }
        return this;
    }
    public ResourceList<T> withFilter(String key, DateTime date) {
        if (isNotBlank(key) && date != null) {
            filters.put(key, date.toString());
        }
        return this;
    }
    @JsonAnyGetter
    public Map<String, String> getFilters() {
        return ImmutableMap.copyOf(filters);
    }
    @JsonAnySetter
    private void setFilter(String key, String value) {
        if (isNotBlank(key) && isNotBlank(value)) {
            filters.put(key, value);    
        }
    }
}