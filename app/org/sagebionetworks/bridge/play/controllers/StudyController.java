package org.sagebionetworks.bridge.play.controllers;

import static org.sagebionetworks.bridge.Roles.ADMIN;
import static org.sagebionetworks.bridge.Roles.DEVELOPER;
import static org.sagebionetworks.bridge.Roles.RESEARCHER;
import static org.sagebionetworks.bridge.Roles.WORKER;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import play.libs.Json;
import play.mvc.BodyParser;
import play.mvc.Result;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.config.BridgeConfigFactory;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.models.CmsPublicKey;
import org.sagebionetworks.bridge.models.ForwardCursorPagedResourceList;
import org.sagebionetworks.bridge.models.ResourceList;
import org.sagebionetworks.bridge.models.VersionHolder;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.EmailVerificationStatusHolder;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyAndUsers;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.studies.StudyIdentifierImpl;
import org.sagebionetworks.bridge.models.studies.SynapseProjectIdTeamIdHolder;
import org.sagebionetworks.bridge.models.upload.UploadView;
import org.sagebionetworks.bridge.services.EmailVerificationService;
import org.sagebionetworks.bridge.services.EmailVerificationStatus;
import org.sagebionetworks.bridge.services.StudyEmailType;
import org.sagebionetworks.bridge.services.UploadCertificateService;
import org.sagebionetworks.bridge.services.UploadService;

@Controller
public class StudyController extends BaseController {

    private final Comparator<Study> STUDY_COMPARATOR = new Comparator<Study>() {
        public int compare(Study study1, Study study2) {
            return study1.getName().compareToIgnoreCase(study2.getName());
        }
    };

    private final Set<String> studyWhitelist = Collections
            .unmodifiableSet(new HashSet<>(BridgeConfigFactory.getConfig().getPropertyAsList("study.whitelist")));

    private UploadCertificateService uploadCertificateService;

    private EmailVerificationService emailVerificationService;

    private UploadService uploadService;

    @Autowired
    final void setUploadCertificateService(UploadCertificateService uploadCertificateService) {
        this.uploadCertificateService = uploadCertificateService;
    }

    @Autowired
    final void setEmailVerificationService(EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }

    @Autowired
    final void setUploadService(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    public Result getCurrentStudy() throws Exception {
        UserSession session = getAuthenticatedSession(DEVELOPER, RESEARCHER, ADMIN);
        Study study = studyService.getStudy(session.getStudyIdentifier());

        return okResult(study);
    }

    public Result updateStudyForDeveloper() throws Exception {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        StudyIdentifier studyId = session.getStudyIdentifier();

        Study studyUpdate = parseJson(request(), Study.class);
        studyUpdate.setIdentifier(studyId.getIdentifier());
        studyUpdate = studyService.updateStudy(studyUpdate, false);
        return okResult(new VersionHolder(studyUpdate.getVersion()));
    }

    public Result updateStudy(String identifier) throws Exception {
        getAuthenticatedSession(ADMIN);

        Study studyUpdate = parseJson(request(), Study.class);
        studyUpdate.setIdentifier(identifier);
        studyUpdate = studyService.updateStudy(studyUpdate, true);
        return okResult(new VersionHolder(studyUpdate.getVersion()));
    }

    public Result getStudy(String identifier) throws Exception {
        getAuthenticatedSession(ADMIN, WORKER);

        // since only admin and worker can call this method, we need to return all studies including deactivated ones
        Study study = studyService.getStudy(identifier, true);
        return okResult(study);
    }

    // You can get a truncated view of studies with either format=summary or summary=true;
    // the latter allows us to make this a boolean flag in the Java client libraries.
    public Result getAllStudies(String format, String summary) throws Exception {
        List<Study> studies = studyService.getStudies();
        if ("summary".equals(format) || "true".equals(summary)) {
            // then only return active study as summary
            List<Study> activeStudiesSummary = studies.stream()
                    .filter(s -> s.isActive()).collect(Collectors.toList());
            Collections.sort(activeStudiesSummary, STUDY_COMPARATOR);
            return okResult(Study.STUDY_LIST_WRITER, new ResourceList<Study>(activeStudiesSummary)
                    .withRequestParam("summary", true));
        }
        getAuthenticatedSession(ADMIN);

        // otherwise, return all studies including deactivated ones
        return okResult(new ResourceList<>(studies).withRequestParam("summary", false));
    }

    public Result createStudy() throws Exception {
        getAuthenticatedSession(ADMIN);

        Study study = parseJson(request(), Study.class);
        study = studyService.createStudy(study);
        return okResult(new VersionHolder(study.getVersion()));
    }

    public Result createStudyAndUsers() throws Exception {
        getAuthenticatedSession(ADMIN);

        StudyAndUsers studyAndUsers = parseJson(request(), StudyAndUsers.class);
        Study study = studyService.createStudyAndUsers(studyAndUsers);

        return createdResult(new VersionHolder(study.getVersion()));
    }

    public Result createSynapse() throws Exception {
        // first get current study
        UserSession session = getAuthenticatedSession(DEVELOPER);
        Study study = studyService.getStudy(session.getStudyIdentifier());

        // then create project and team and grant admin permission to current user and exporter
        List<String> userIds = Arrays.asList(parseJson(request(), String[].class));
        studyService.createSynapseProjectTeam(ImmutableList.copyOf(userIds), study);

        return createdResult(new SynapseProjectIdTeamIdHolder(study.getSynapseProjectId(), study.getSynapseDataAccessTeamId()));
    }

    // since only admin can delete study, no need to check if return results should contain deactivated ones
    public Result deleteStudy(String identifier, String physical) throws Exception {
        getAuthenticatedSession(ADMIN);
        if (studyWhitelist.contains(identifier)) {
            return forbidden(Json.toJson(identifier + " is protected by whitelist."));
        }

        studyService.deleteStudy(identifier, Boolean.valueOf(physical));

        return okResult("Study deleted.");
    }

    public Result getStudyPublicKeyAsPem() throws Exception {
        UserSession session = getAuthenticatedSession(DEVELOPER);

        String pem = uploadCertificateService.getPublicKeyAsPem(session.getStudyIdentifier());

        return okResult(new CmsPublicKey(pem));
    }

    public Result getEmailStatus() throws Exception {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        Study study = studyService.getStudy(session.getStudyIdentifier());

        EmailVerificationStatus status = emailVerificationService.getEmailStatus(study.getSupportEmail());
        return okResult(new EmailVerificationStatusHolder(status));
    }

    /** Resends the verification email for the current study's email. */
    @BodyParser.Of(BodyParser.Empty.class)
    public Result resendVerifyEmail(String type) {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        StudyEmailType parsedType = parseEmailType(type);
        studyService.sendVerifyEmail(session.getStudyIdentifier(), parsedType);
        return okResult("Resending verification email for consent notification email.");
    }

    /**
     * Verifies the emails for the study. Since this comes in from an email with a token, you don't need to be
     * authenticated. The token itself knows what study this is for.
     */
    @BodyParser.Of(BodyParser.Empty.class)
    public Result verifyEmail(String identifier, String token, String type) {
        StudyEmailType parsedType = parseEmailType(type);
        studyService.verifyEmail(new StudyIdentifierImpl(identifier), token, parsedType);
        return okResult("Consent notification email address verified.");
    }

    // Helper method to parse and validate the email type for study email verification workflow. We do verification
    // here so that the service can just deal with a clean enum.
    private static StudyEmailType parseEmailType(String typeStr) {
        if (StringUtils.isBlank(typeStr)) {
            throw new BadRequestException("Email type must be specified");
        }

        try {
            return StudyEmailType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unrecognized type \"" + typeStr + "\"");
        }
    }

    @BodyParser.Of(BodyParser.Empty.class)
    public Result verifySenderEmail() throws Exception {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        Study study = studyService.getStudy(session.getStudyIdentifier());

        EmailVerificationStatus status = emailVerificationService.verifyEmailAddress(study.getSupportEmail());
        return okResult(new EmailVerificationStatusHolder(status));
    }

    public Result getUploads(String startTimeString, String endTimeString, Integer pageSize, String offsetKey) {
        UserSession session = getAuthenticatedSession(DEVELOPER);

        DateTime startTime = BridgeUtils.getDateTimeOrDefault(startTimeString, null);
        DateTime endTime = BridgeUtils.getDateTimeOrDefault(endTimeString, null);

        ForwardCursorPagedResourceList<UploadView> uploads = uploadService.getStudyUploads(
                session.getStudyIdentifier(), startTime, endTime, pageSize, offsetKey);

        return okResult(uploads);
    }

    /**
     * another version of getUploads for workers to specify any studyid to get uploads
     * @param startTimeString
     * @param endTimeString
     * @return
     */
    public Result getUploadsForStudy(String studyIdString, String startTimeString, String endTimeString, Integer pageSize, String offsetKey) throws EntityNotFoundException {
        getAuthenticatedSession(WORKER);

        DateTime startTime = BridgeUtils.getDateTimeOrDefault(startTimeString, null);
        DateTime endTime = BridgeUtils.getDateTimeOrDefault(endTimeString, null);

        StudyIdentifier studyId = new StudyIdentifierImpl(studyIdString);

        ForwardCursorPagedResourceList<UploadView> uploads = uploadService.getStudyUploads(
                studyId, startTime, endTime, pageSize, offsetKey);

        return okResult(uploads);
    }
}
