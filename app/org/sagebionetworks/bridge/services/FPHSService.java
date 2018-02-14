package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkNotNull;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.sagebionetworks.bridge.dao.ParticipantOption.DATA_GROUPS;
import static org.sagebionetworks.bridge.dao.ParticipantOption.EXTERNAL_IDENTIFIER;

import java.util.List;
import java.util.Set;

import org.sagebionetworks.bridge.dao.FPHSExternalIdentifierDao;
import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifier;
import org.sagebionetworks.bridge.models.accounts.FPHSExternalIdentifier;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FPHSService {
    
    private FPHSExternalIdentifierDao fphsDao;
    private ParticipantOptionsService optionsService;

    @Autowired
    final void setFPHSExternalIdentifierDao(FPHSExternalIdentifierDao dao) {
        this.fphsDao = dao;
    }
    @Autowired
    final void setParticipantOptionsService(ParticipantOptionsService options) {
        this.optionsService = options;
    }
    
    public void verifyExternalIdentifier(ExternalIdentifier externalId) throws Exception {
        checkNotNull(externalId);
        
        if (isBlank(externalId.getIdentifier())) {
            throw new InvalidEntityException(externalId, "ExternalIdentifier is not valid");
        }
        // Throws exception if not verified
        fphsDao.verifyExternalId(externalId);
    }
    public void registerExternalIdentifier(StudyIdentifier studyId, String healthCode, ExternalIdentifier externalId) throws Exception {
        checkNotNull(studyId);
        checkNotNull(healthCode);
        checkNotNull(externalId);
        
        if (isBlank(externalId.getIdentifier())) {
            throw new InvalidEntityException(externalId, "ExternalIdentifier is not valid");
        }
        verifyExternalIdentifier(externalId);
        
        Set<String> dataGroups = optionsService.getOptions(studyId, healthCode).getStringSet(DATA_GROUPS);
        dataGroups.add("football_player");
        optionsService.setString(studyId, healthCode, EXTERNAL_IDENTIFIER, externalId.getIdentifier());
        optionsService.setStringSet(studyId, healthCode, DATA_GROUPS, dataGroups);
        fphsDao.registerExternalId(externalId);
    }
    
    /**
     * Get all FPHS identifiers along with information about which ones have been used to register.
     * 
     * @return
     * @throws Exception
     */
    public List<FPHSExternalIdentifier> getExternalIdentifiers() throws Exception {
        return fphsDao.getExternalIds();
    }
    
    /**
     * Add new external identifiers to the database. This will not overwrite the registration status of existing
     * external IDs.
     * 
     * @param externalIds
     * @throws Exception
     */
    public void addExternalIdentifiers(List<FPHSExternalIdentifier> externalIds) throws Exception {
        checkNotNull(externalIds);
        fphsDao.addExternalIds(externalIds);
    }
}
