package org.sagebionetworks.bridge.models;

import java.util.List;

import org.joda.time.DateTime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A paged list of items from a data store that can provide a pointer to the next page of records, but 
 * which cannot return the total number of records across all pages (DynamoDB).
 */
public class ForwardCursorPagedResourceList<T> extends ResourceList<T> {

    private final String offsetKey;
    private final int pageSize;

    @JsonCreator
    public ForwardCursorPagedResourceList(
            @JsonProperty("items") List<T> items,
            @JsonProperty("offsetKey") String offsetKey,
            @JsonProperty("pageSize") int pageSize) {
        super(items);
        this.offsetKey = offsetKey;
        this.pageSize = pageSize;
    }

    @JsonProperty("hasNext")
    public boolean hasNext() {
        return offsetKey != null;
    }
    public int getPageSize() {
        return pageSize;
    }
    public String getOffsetKey() {
        return offsetKey;
    }
    public ForwardCursorPagedResourceList<T> withFilter(String key, String value) {
        super.withFilter(key, value);
        return this;
    }
    public ForwardCursorPagedResourceList<T> withFilter(String key, DateTime date) {
        super.withFilter(key, date);
        return this;
    }
    @Override
    public String toString() {
        return "ForwardCursorPagedResourceList [items=" + getItems() + ", pageSize=" + pageSize + ", filters=" + getFilters() + "]";
    }

}
