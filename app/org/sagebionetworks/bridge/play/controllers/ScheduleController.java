package org.sagebionetworks.bridge.play.controllers;

import java.util.Collections;
import java.util.List;

import org.sagebionetworks.bridge.models.ClientInfo;
import org.sagebionetworks.bridge.models.ResourceList;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.schedules.Schedule;
import org.sagebionetworks.bridge.models.schedules.ScheduleContext;
import org.sagebionetworks.bridge.models.schedules.SchedulePlan;
import org.sagebionetworks.bridge.models.schedules.ScheduleType;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.services.SchedulePlanService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

import play.mvc.Result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Lists;

@Controller
public class ScheduleController extends BaseController {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleController.class);
    
    private SchedulePlanService schedulePlanService;
    
    @Autowired
    public void setSchedulePlanService(SchedulePlanService schedulePlanService) {
        this.schedulePlanService = schedulePlanService;
    }
    
    @Deprecated
    public Result getSchedulesV1() throws Exception {
        getConsentedSession();
        return okResult(Collections.emptyList());
    }
    
    @Deprecated
    public Result getSchedulesV3() throws Exception {
        List<Schedule> schedules = getSchedulesInternal();
        
        JsonNode node = MAPPER.valueToTree(new ResourceList<Schedule>(schedules));
        ArrayNode items = (ArrayNode)node.get("items");
        for (int i=0; i < items.size(); i++) {
            // If the schedule has this cron string, make it a recurring, "persistent" schedule
            if ("0 0 12 1/1 * ? *".equals(schedules.get(i).getCronTrigger())) {
                ((ObjectNode)items.get(i)).put("scheduleType", ScheduleType.RECURRING.name().toLowerCase());
                ((ObjectNode)items.get(i)).put("persistent", true);
            }
        }
        return ok(node);
    }
    
    public Result getSchedules() throws Exception {
        List<Schedule> schedules = getSchedulesInternal();
        return okResult(schedules);
    }
    
    private List<Schedule> getSchedulesInternal() {
        UserSession session = getConsentedSession();
        StudyIdentifier studyId = session.getStudyIdentifier();
        ClientInfo clientInfo = getClientInfoFromUserAgentHeader();

        ScheduleContext context = new ScheduleContext.Builder()
                .withLanguages(getLanguages(session))
                .withStudyIdentifier(studyId)
                .withHealthCode(session.getHealthCode())
                .withUserId(session.getId())
                .withClientInfo(clientInfo).build();
        
        List<SchedulePlan> plans = schedulePlanService.getSchedulePlans(clientInfo, studyId);

        List<Schedule> schedules = Lists.newArrayListWithCapacity(plans.size());
        for (SchedulePlan plan : plans) {
            Schedule schedule = plan.getStrategy().getScheduleForUser(plan, context);
            if (schedule != null) {
                schedules.add(schedule);
            } else {
                LOG.warn("Schedule plan "+plan.getLabel()+" has no schedule for user "+session.getId());
            }
        }
        return schedules;
    }
}
