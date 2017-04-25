package org.sagebionetworks.bridge.services.backfill;

import java.util.Iterator;

import javax.annotation.Resource;

import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.models.accounts.AccountSummary;
import org.sagebionetworks.bridge.models.backfill.BackfillTask;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.services.StudyService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Backfills health ID and health code.
 */
@Component
public class HealthCodeBackfill extends AsyncBackfillTemplate {
    private AccountDao accountDao;
    private StudyService studyService;

    @Resource(name = "oktaAccountDao")
    public void setAccountDao(AccountDao accountDao) {
        this.accountDao = accountDao;
    }

    @Autowired
    public void setStudyService(StudyService studyService) {
        this.studyService = studyService;
    }

    @Override
    int getLockExpireInSeconds() {
        return 30 * 60;
    }

    @Override
    void doBackfill(final BackfillTask task, BackfillCallback callback) {
        for (Iterator<AccountSummary> i = accountDao.getAllAccounts(); i.hasNext();) {
            AccountSummary summary = i.next();
            Study study = studyService.getStudy(summary.getStudyIdentifier());
            
            // getting the individual account is sufficient to create a mapping if it does not exist.
            accountDao.getAccount(study, summary.getId());
        }
    }
}
