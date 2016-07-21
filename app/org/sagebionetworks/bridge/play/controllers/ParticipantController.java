package org.sagebionetworks.bridge.play.controllers;

import static java.lang.Integer.parseInt;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.sagebionetworks.bridge.Roles.RESEARCHER;
import static org.sagebionetworks.bridge.BridgeConstants.NO_CALLER_ROLES;

import java.util.Set;

import static org.sagebionetworks.bridge.BridgeConstants.API_DEFAULT_PAGE_SIZE;

import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

import org.sagebionetworks.bridge.BridgeConstants;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.models.CriteriaContext;
import org.sagebionetworks.bridge.models.DateTimeRangeResourceList;
import org.sagebionetworks.bridge.models.PagedResourceList;
import org.sagebionetworks.bridge.models.accounts.AccountSummary;
import org.sagebionetworks.bridge.models.accounts.IdentifierHolder;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.accounts.UserSessionInfo;
import org.sagebionetworks.bridge.models.accounts.Withdrawal;
import org.sagebionetworks.bridge.models.schedules.ScheduledActivity;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.subpopulations.SubpopulationGuid;
import org.sagebionetworks.bridge.models.upload.Upload;
import org.sagebionetworks.bridge.services.ParticipantService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.Sets;

import play.mvc.Result;

@Controller
public class ParticipantController extends BaseController {
    
    private ParticipantService participantService;
    
    @Autowired
    final void setParticipantService(ParticipantService participantService) {
        this.participantService = participantService;
    }
    
    public Result getSelfParticipant() throws Exception {
        UserSession session = getSessionInRole();
        Study study = studyService.getStudy(session.getStudyIdentifier());
        
        StudyParticipant participant = participantService.getParticipant(study, session.getId(), false);
        
        String ser = StudyParticipant.API_NO_HEALTH_CODE_WRITER.writeValueAsString(participant);
        
        return ok(ser).as(BridgeConstants.JSON_MIME_TYPE);
    }
    
    public Result updateSelfParticipant() throws Exception {
        UserSession session = getSessionInRole();
        Study study = studyService.getStudy(session.getStudyIdentifier());
        
        // By copying only values that were included in the JSON onto the existing StudyParticipant,
        // we allow clients to only send back partial JSON to update the user. This has been the 
        // usage pattern in prior APIs and it will make refactoring to use this API easier.
        JsonNode node = requestToJSON(request());
        Set<String> fieldNames = Sets.newHashSet(node.fieldNames());

        StudyParticipant participant = MAPPER.treeToValue(node, StudyParticipant.class);
        StudyParticipant existing = participantService.getParticipant(study, session.getId(), false);
        StudyParticipant updated = new StudyParticipant.Builder()
                .copyOf(existing)
                .copyFieldsOf(participant, fieldNames)
                .withId(session.getId()).build();
        participantService.updateParticipant(study, NO_CALLER_ROLES, updated);
        
        CriteriaContext context = getCriteriaContext(session);
        session = authenticationService.getSession(study, context);
        updateSession(session);
        
        return okResult(UserSessionInfo.toJSON(session));
    }
    
    public Result getParticipants(String offsetByString, String pageSizeString, String emailFilter) {
        UserSession session = getSessionInRole(RESEARCHER);
        
        Study study = studyService.getStudy(session.getStudyIdentifier());
        int offsetBy = getIntOrDefault(offsetByString, 0);
        int pageSize = getIntOrDefault(pageSizeString, API_DEFAULT_PAGE_SIZE);
        
        PagedResourceList<AccountSummary> page = participantService.getPagedAccountSummaries(study, offsetBy, pageSize, emailFilter);
        return okResult(page);
    }
    
    public Result createParticipant() throws Exception {
        UserSession session = getSessionInRole(RESEARCHER);
        Study study = studyService.getStudy(session.getStudyIdentifier());
        
        StudyParticipant participant = parseJson(request(), StudyParticipant.class);
        
        IdentifierHolder holder = participantService.createParticipant(study, session.getParticipant().getRoles(),
                participant, true);
        return createdResult(holder);
    }
    
    public Result getParticipant(String userId) throws Exception {
        UserSession session = getSessionInRole(RESEARCHER);
        Study study = studyService.getStudy(session.getStudyIdentifier());

        StudyParticipant participant = participantService.getParticipant(study, userId, true);

        ObjectWriter writer = (study.isHealthCodeExportEnabled()) ?
                StudyParticipant.API_WITH_HEALTH_CODE_WRITER :
                StudyParticipant.API_NO_HEALTH_CODE_WRITER;
        String ser = writer.writeValueAsString(participant);

        return ok(ser).as(BridgeConstants.JSON_MIME_TYPE);
    }
    
    public Result updateParticipant(String userId) {
        UserSession session = getSessionInRole(RESEARCHER);
        Study study = studyService.getStudy(session.getStudyIdentifier());

        StudyParticipant participant = parseJson(request(), StudyParticipant.class);
 
        participant = new StudyParticipant.Builder()
                .copyOf(participant)
                .withId(userId).build();
        participantService.updateParticipant(study, session.getParticipant().getRoles(), participant);

        return okResult("Participant updated.");
    }
    
    public Result signOut(String userId) throws Exception {
        UserSession session = getSessionInRole(RESEARCHER);
        Study study = studyService.getStudy(session.getStudyIdentifier());

        participantService.signUserOut(study, userId);

        return okResult("User signed out.");
    }

    public Result requestResetPassword(String userId) throws Exception {
        UserSession session = getSessionInRole(RESEARCHER);
        Study study = studyService.getStudy(session.getStudyIdentifier());

        participantService.requestResetPassword(study, userId);
        
        return okResult("Request to reset password sent to user.");
    }
    
    public Result getActivityHistory(String userId, String offsetKey, String pageSizeString) throws Exception {
        UserSession session = getSessionInRole(RESEARCHER);
        Study study = studyService.getStudy(session.getStudyIdentifier());
        
        Integer pageSize = (pageSizeString != null) ? Integer.parseInt(pageSizeString,10) : null;
        
        PagedResourceList<? extends ScheduledActivity> history = participantService.getActivityHistory(study, userId, offsetKey, pageSize);
        
        return ok(ScheduledActivity.RESEARCHER_SCHEDULED_ACTIVITY_WRITER.writeValueAsString(history));
    }
    
    public Result deleteActivities(String userId) throws Exception {
        UserSession session = getSessionInRole(RESEARCHER);
        Study study = studyService.getStudy(session.getStudyIdentifier());

        participantService.deleteActivities(study, userId);
        
        return okResult("Scheduled activities deleted.");
    }
    
    public Result resendEmailVerification(String userId) {
        UserSession session = getSessionInRole(RESEARCHER);
        Study study = studyService.getStudy(session.getStudyIdentifier());

        participantService.resendEmailVerification(study, userId);
        
        return okResult("Email verification request has been resent to user.");
    }
    
    public Result resendConsentAgreement(String userId, String guid) {
        UserSession session = getSessionInRole(RESEARCHER);
        Study study = studyService.getStudy(session.getStudyIdentifier());
        
        SubpopulationGuid subpopGuid = SubpopulationGuid.create(guid);
        participantService.resendConsentAgreement(study, subpopGuid, userId);
        
        return okResult("Consent agreement resent to user.");
    }
    
    public Result withdrawFromAllConsents(String userId) {
        UserSession session = getSessionInRole(RESEARCHER);
        Study study = studyService.getStudy(session.getStudyIdentifier());
        
        Withdrawal withdrawal = parseJson(request(), Withdrawal.class);
        long withdrewOn = DateTime.now().getMillis();
        
        participantService.withdrawAllConsents(study, userId, withdrawal, withdrewOn);
        
        return okResult("User has been withdrawn from the study.");
    }
    
    public Result getUploads(String userId, String startTimeString, String endTimeString) {
        UserSession session = getSessionInRole(RESEARCHER);
        Study study = studyService.getStudy(session.getStudyIdentifier());
        
        DateTime startTime = getDateTimeOrDefault(startTimeString, null);
        DateTime endTime = getDateTimeOrDefault(endTimeString, null);
        
        DateTimeRangeResourceList<? extends Upload> uploads = participantService.getUploads(
                study, userId, startTime, endTime);

        return okResult(uploads);
    }
    
    private int getIntOrDefault(String value, int defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            return parseInt(value);
        } catch(NumberFormatException e) {
            throw new BadRequestException(value + " is not an integer");
        }
    }
    
    private DateTime getDateTimeOrDefault(String value, DateTime defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            return DateTime.parse(value);
        } catch(Exception e) {
            throw new BadRequestException(value + " is not an ISO timestamp");
        }
    }

}
