package org.sagebionetworks.bridge.play.controllers;

import static org.sagebionetworks.bridge.dao.ParticipantOption.DATA_GROUPS;
import static org.sagebionetworks.bridge.BridgeConstants.NO_CALLER_ROLES;
import static org.sagebionetworks.bridge.cache.ViewCache.ViewCacheKey;

import java.util.Map;
import java.util.Set;

import org.sagebionetworks.bridge.BridgeConstants;
import org.sagebionetworks.bridge.cache.ViewCache;
import org.sagebionetworks.bridge.json.JsonUtils;
import org.sagebionetworks.bridge.models.CriteriaContext;
import org.sagebionetworks.bridge.models.accounts.ConsentStatus;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifier;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.subpopulations.SubpopulationGuid;
import org.sagebionetworks.bridge.services.ConsentService;
import org.sagebionetworks.bridge.services.ExternalIdService;
import org.sagebionetworks.bridge.services.ParticipantService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Supplier;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import play.mvc.Result;

@Controller
public class UserProfileController extends BaseController {
    
    private static final String FIRST_NAME_FIELD = "firstName";
    private static final String LAST_NAME_FIELD = "lastName";
    private static final String EMAIL_FIELD = "email";
    private static final String USERNAME_FIELD = "username";
    private static final String TYPE_FIELD = "type";
    private static final String TYPE_VALUE = "UserProfile";
    private static final Set<String> DATA_GROUPS_SET = Sets.newHashSet("dataGroups");

    private ParticipantService participantService;
    
    private ConsentService consentService;
    
    private ExternalIdService externalIdService;
    
    private ViewCache viewCache;

    @Autowired
    public final void setParticipantService(ParticipantService participantService) {
        this.participantService = participantService;
    }
    @Autowired
    public final void setViewCache(ViewCache viewCache) {
        this.viewCache = viewCache;
    }
    @Autowired
    public final void setExternalIdService(ExternalIdService externalIdService) {
        this.externalIdService = externalIdService;
    }
    @Autowired
    public final void setConsentService(ConsentService consentService) {
        this.consentService = consentService;
    }

    public Result getUserProfile() throws Exception {
        final UserSession session = getAuthenticatedSession();
        final Study study = studyService.getStudy(session.getStudyIdentifier());
        final String userId = session.getId();
        
        ViewCacheKey cacheKey = new ViewCacheKey(ObjectNode.class, session.getStudyIdentifier(), userId);
        String json = viewCache.getView(cacheKey, new Supplier<ObjectNode>() {
            @Override public ObjectNode get() {
                StudyParticipant participant = participantService.getParticipant(study, userId, false);
                ObjectNode node = JsonNodeFactory.instance.objectNode();
                if (participant.getFirstName() != null) {
                    node.put(FIRST_NAME_FIELD, participant.getFirstName());    
                }
                if (participant.getLastName() != null) {
                    node.put(LAST_NAME_FIELD, participant.getLastName());    
                }
                node.put(EMAIL_FIELD, participant.getEmail());
                node.put(USERNAME_FIELD, participant.getEmail());
                node.put(TYPE_FIELD, TYPE_VALUE);
                for (Map.Entry<String,String> entry : participant.getAttributes().entrySet()) {
                    node.put(entry.getKey(), entry.getValue());    
                }
                return node;
            }
        });
        return ok(json).as(BridgeConstants.JSON_MIME_TYPE);
    }

    public Result updateUserProfile() throws Exception {
        UserSession session = getAuthenticatedSession();
        Study study = studyService.getStudy(session.getStudyIdentifier());
        String userId = session.getId();
        
        JsonNode node = requestToJSON(request());
        Map<String,String> attributes = Maps.newHashMap();
        for (String attrKey : study.getUserProfileAttributes()) {
            if (node.has(attrKey)) {
                attributes.put(attrKey, node.get(attrKey).asText());
            }
        }
        
        StudyParticipant participant = participantService.getParticipant(study, userId, false);
        
        StudyParticipant updated = new StudyParticipant.Builder().copyOf(participant)
                .withFirstName(JsonUtils.asText(node, "firstName"))
                .withLastName(JsonUtils.asText(node, "lastName"))
                .withAttributes(attributes)
                .withId(userId).build();
        participantService.updateParticipant(study, NO_CALLER_ROLES, updated);
        
        session.setParticipant(updated);
        updateSession(session);
        
        ViewCacheKey cacheKey = new ViewCacheKey(ObjectNode.class, session.getStudyIdentifier(), userId);
        viewCache.removeView(cacheKey);
        
        return okResult("Profile updated.");
    }
    
    public Result createExternalIdentifier() throws Exception {
        UserSession session = getAuthenticatedSession();
        Study study = studyService.getStudy(session.getStudyIdentifier());
        
        ExternalIdentifier externalId = parseJson(request(), ExternalIdentifier.class);

        externalIdService.assignExternalId(study, externalId.getIdentifier(), session.getHealthCode());
        
        return okResult("External identifier added to user profile.");
    }
    
    public Result getDataGroups() throws Exception {
        UserSession session = getAuthenticatedSession();
        
        Set<String> dataGroups = optionsService.getOptions(
                session.getHealthCode()).getStringSet(DATA_GROUPS);
        
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        dataGroups.stream().forEach(array::add);

        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.set("dataGroups", array);
        node.put(TYPE_FIELD, "DataGroups");

        return ok(node).as(BridgeConstants.JSON_MIME_TYPE);
    }
    
    public Result updateDataGroups() throws Exception {
        UserSession session = getAuthenticatedSession();
        Study study = studyService.getStudy(session.getStudyIdentifier());
        
        StudyParticipant participant = participantService.getParticipant(study, session.getId(), false);
        
        StudyParticipant dataGroups = parseJson(request(), StudyParticipant.class);
        
        StudyParticipant updated = new StudyParticipant.Builder().copyOf(participant)
                .copyFieldsOf(dataGroups, DATA_GROUPS_SET)
                .withId(session.getId()).build();
        
        participantService.updateParticipant(study, NO_CALLER_ROLES, updated);
        
        session.setParticipant(updated);
        
        CriteriaContext context = getCriteriaContext(session);
        
        Map<SubpopulationGuid,ConsentStatus> statuses = consentService.getConsentStatuses(context);
        session.setConsentStatuses(statuses);
                
        updateSession(session);
        return okResult("Data groups updated.");
    }
    
}
