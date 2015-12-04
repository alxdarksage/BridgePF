package org.sagebionetworks.bridge.services.backfill;

import java.util.Iterator;

import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.dao.UserConsentDao;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.HealthId;
import org.sagebionetworks.bridge.models.backfill.BackfillTask;
import org.sagebionetworks.bridge.services.HealthCodeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("userConsentEventBackfill")
public class UserConsentBackfill extends AsyncBackfillTemplate {

    private AccountDao accountDao;
    private UserConsentDao userConsentDao;
    private HealthCodeService healthCodeService;
    
    @Autowired
    private final void setAccountDao(AccountDao accountDao) {
        this.accountDao = accountDao;
    }
    @Autowired
    private final void setUserConsentDao(UserConsentDao userConsentDao) {
        this.userConsentDao = userConsentDao;
    }
    @Autowired
    public final void setHealthCodeService(HealthCodeService healthCodeService) {
        this.healthCodeService = healthCodeService;
    }
    
    @Override
    int getLockExpireInSeconds() {
        return 6*60*60; // 21,600 seconds, or six hours
    }

    @Override
    void doBackfill(BackfillTask task, BackfillCallback callback) {
        callback.newRecords(getBackfillRecordFactory().createOnly(task, "Starting to examine accounts"));
        int count = 0;
        
        // Reducing the logging to speed up the process a little, and clear out some noise, so we can easily see
        // when a record is migrated.
        Iterator<Account> i = accountDao.getAllAccounts();
        while (i.hasNext()) {
            Account account = i.next();
            
            //callback.newRecords(getBackfillRecordFactory().createOnly(task, "Examining account: " + account.getId()));
            
            HealthId mapping = healthCodeService.getMapping(account.getHealthId());
            if (mapping != null) {
                String healthCode = mapping.getCode();
                boolean success = userConsentDao.migrateConsent(healthCode, account.getStudyIdentifier());
                if (success) {
                    callback.newRecords(getBackfillRecordFactory().createOnly(task, "Consent record migrated"));
                } else {
                    //callback.newRecords(getBackfillRecordFactory().createOnly(task, "Could not migrate consent record"));
                }
            }  else {
                //callback.newRecords(getBackfillRecordFactory().createOnly(task, "Health code not found"));
            }
            if (count % 100 == 0) {
                callback.newRecords(getBackfillRecordFactory().createOnly(task, Integer.toString(count)));
            }
            count++;
        }
    }

}