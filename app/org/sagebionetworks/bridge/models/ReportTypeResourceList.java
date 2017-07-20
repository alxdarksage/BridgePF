package org.sagebionetworks.bridge.models;

import java.util.List;
import java.util.Map;

import org.sagebionetworks.bridge.models.reports.ReportType;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ReportTypeResourceList<T> extends ResourceList<T> {
    private final ReportType reportType;

    @JsonCreator
    public ReportTypeResourceList(@JsonProperty("items") List<T> items,
            @JsonProperty("reportType") ReportType reportType) {
        super(items);
        this.reportType = reportType;
    }
    public ReportType getReportType() {
        return reportType;
    }
    @Override
    public Map<String, Object> getRequestParams() {
        Map<String, Object> map = super.getRequestParams();
        if (reportType != null) {
            map.put("reportType", reportType.name().toLowerCase());    
        }
        return map;
    }
}
