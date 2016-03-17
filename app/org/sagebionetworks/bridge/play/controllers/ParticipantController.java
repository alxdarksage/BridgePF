package org.sagebionetworks.bridge.play.controllers;

import static java.lang.Integer.parseInt;
import static org.sagebionetworks.bridge.Roles.RESEARCHER;

import static org.sagebionetworks.bridge.BridgeConstants.API_DEFAULT_PAGE_SIZE;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.models.PagedResourceList;
import org.sagebionetworks.bridge.models.accounts.AccountSummary;
import org.sagebionetworks.bridge.models.accounts.ParticipantOptions;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant2;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.services.ParticipantService;

import play.mvc.Result;

@Controller
public class ParticipantController extends BaseController {
    
    private ParticipantService participantService;
    
    @Autowired
    final void setParticipantService(ParticipantService participantService) {
        this.participantService = participantService;
    }
    
    public Result getParticipant(String email) {
        UserSession session = getAuthenticatedSession(RESEARCHER);
        
        Study study = studyService.getStudy(session.getStudyIdentifier());
        
        StudyParticipant2 participant = participantService.getParticipant(study, email);
        return okResult(participant);
    }
    
    public Result getParticipants(String offsetByString, String pageSizeString) {
        UserSession session = getAuthenticatedSession(RESEARCHER);
        
        Study study = studyService.getStudy(session.getStudyIdentifier());
        int offsetBy = getIntOrDefault(offsetByString, 0);
        int pageSize = getIntOrDefault(pageSizeString, API_DEFAULT_PAGE_SIZE);
        
        PagedResourceList<AccountSummary> page = participantService.getPagedAccountSummaries(study, offsetBy, pageSize);
        return okResult(page);
    }
    
    public Result updateParticipantOptions(String email) {
        UserSession session = getAuthenticatedSession(RESEARCHER);
        
        Study study = studyService.getStudy(session.getStudyIdentifier());
        ParticipantOptions options = parseJson(request(), ParticipantOptions.class);
        
        participantService.updateParticipantOptions(study, email, options);
        
        return okResult("Participant options updated.");
    }
    
    private int getIntOrDefault(String value, int defaultValue) {
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        try {
            return parseInt(value);
        } catch(NumberFormatException e) {
            throw new BadRequestException(value + " is not an integer");
        }
    }

}
