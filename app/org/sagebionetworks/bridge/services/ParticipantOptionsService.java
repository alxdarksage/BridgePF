package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.sagebionetworks.bridge.dao.ParticipantOption.DATA_GROUPS;
import static org.sagebionetworks.bridge.dao.ParticipantOption.LANGUAGES;
import static org.sagebionetworks.bridge.dao.ParticipantOption.EMAIL_NOTIFICATIONS;
import static org.sagebionetworks.bridge.dao.ParticipantOption.EXTERNAL_IDENTIFIER;
import static org.sagebionetworks.bridge.dao.ParticipantOption.SHARING_SCOPE;
import static org.sagebionetworks.bridge.dao.ParticipantOption.TIME_ZONE;

import java.util.LinkedHashSet;
import java.util.Set;

import org.joda.time.DateTimeZone;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.common.collect.ImmutableMap;

import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.dao.ParticipantOption;
import org.sagebionetworks.bridge.dao.ParticipantOption.SharingScope;
import org.sagebionetworks.bridge.dao.ParticipantOptionsDao;
import org.sagebionetworks.bridge.hibernate.HibernateAccountDao;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.AccountId;
import org.sagebionetworks.bridge.models.accounts.ParticipantOptionsLookup;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;

@Component
public class ParticipantOptionsService {
    
    private ParticipantOptionsDao optionsDao;
    
    private AccountDao accountDao;
    
    @Autowired
    final void setParticipantOptionsDao(ParticipantOptionsDao participantOptionsDao) {
        this.optionsDao = participantOptionsDao;
    }

    @Autowired
    final void setAccountDao(AccountDao accountDao) {
        this.accountDao = accountDao;
    }
    
    /**
     * Get all options and their values for a participant in a lookup object with type-safe 
     * accessors. If a value is not set, the value will be null in the map. A lookup object 
     * will be returned whether any values have been set for this participant or not. 
     */
    public ParticipantOptionsLookup getOptions(StudyIdentifier studyId, String healthCode) {
        checkArgument(isNotBlank(healthCode));
        
        AccountId accountId = AccountId.forHealthCode(studyId.getIdentifier(), healthCode);
        Account account = accountDao.getAccount(accountId);
        // If the account has been migrated, we no longer need to look at the ParticipantOptions table.
        if (account != null && account.getMigrationVersion() == HibernateAccountDao.CURRENT_MIGRATION_VERSION) {
            ImmutableMap.Builder<String,String> builder = new ImmutableMap.Builder<String,String>();
            nullSafeAddToMap(builder, TIME_ZONE, account, null);
            nullSafeAddToMap(builder, SHARING_SCOPE, account, null);
            nullSafeAddToMap(builder, EMAIL_NOTIFICATIONS, account, null);
            nullSafeAddToMap(builder, EXTERNAL_IDENTIFIER, account, null);
            nullSafeAddToMap(builder, DATA_GROUPS, account, null);
            nullSafeAddToMap(builder, LANGUAGES, account, null);
            return new ParticipantOptionsLookup(builder.build());
        }
        return optionsDao.getOptions(healthCode);
        /*
        ParticipantOptionsLookup existingLookup = optionsDao.getOptions(healthCode);
        
        // Otherwise, merge the two tables. This allows us to keep methods that update a single field in the account table 
        // (partial migration). Actual migration cannot overwrite existing values in account table.
        ImmutableMap.Builder<String,String> builder = new ImmutableMap.Builder<String,String>();
        nullSafeAddToMap(builder, TIME_ZONE, account, existingLookup);
        nullSafeAddToMap(builder, SHARING_SCOPE, account, existingLookup);
        nullSafeAddToMap(builder, EMAIL_NOTIFICATIONS, account, existingLookup);
        nullSafeAddToMap(builder, EXTERNAL_IDENTIFIER, account, existingLookup);
        nullSafeAddToMap(builder, DATA_GROUPS, account, existingLookup);
        nullSafeAddToMap(builder, LANGUAGES, account, existingLookup);
        return new ParticipantOptionsLookup(builder.build());
        */
    }
    
    private void nullSafeAddToMap(ImmutableMap.Builder<String, String> builder, ParticipantOption option,
            Account account, ParticipantOptionsLookup existingLookup) {
        String value = (account != null) ? option.fromAccount(account) : null;
        if (value != null) {
            builder.put(option.name(), value);
        } else if (existingLookup != null && existingLookup.getString(option) != null) {
            builder.put(option.name(), existingLookup.getString(option));
        }
    }
    
    /**
     * Persist a boolean participant option.
     */
    public void setBoolean(StudyIdentifier studyIdentifier, String healthCode, ParticipantOption option, boolean value) {
        checkNotNull(studyIdentifier);
        checkArgument(isNotBlank(healthCode));
        checkArgument(option == ParticipantOption.EMAIL_NOTIFICATIONS);

        AccountId accountId = AccountId.forHealthCode(studyIdentifier.getIdentifier(), healthCode);
        Account account = accountDao.getAccount(accountId);
        if (account != null) {
            account.setNotifyByEmail(value);
            accountDao.updateAccount(account, false);
        }
    }

    /**
     * Persist a string participant option.
     */
    public void setString(StudyIdentifier studyIdentifier, String healthCode, ParticipantOption option, String value) {
        checkNotNull(studyIdentifier);
        checkArgument(isNotBlank(healthCode));
        checkNotNull(option == ParticipantOption.EXTERNAL_IDENTIFIER);
        
        AccountId accountId = AccountId.forHealthCode(studyIdentifier.getIdentifier(), healthCode);
        Account account = accountDao.getAccount(accountId);
        if (account != null) {
            account.setExternalId(value);
            accountDao.updateAccount(account, true);
        }
    }

    /**
     * Persist an enumerated participant option.
     */
    public void setEnum(StudyIdentifier studyIdentifier, String healthCode, ParticipantOption option, Enum<?> value) {
        checkNotNull(studyIdentifier);
        checkArgument(isNotBlank(healthCode));
        checkNotNull(option == ParticipantOption.SHARING_SCOPE);

        SharingScope scope = (SharingScope)value;
        
        AccountId accountId = AccountId.forHealthCode(studyIdentifier.getIdentifier(), healthCode);
        Account account = accountDao.getAccount(accountId);
        if (account != null) {
            account.setSharingScope(scope);
            accountDao.updateAccount(account, false);
        }
    }

    /**
     * Persist a string set option. The keys in the string set are persisted in the order they are retrieved from a set, 
     * and returned in that same order.
     */
    public void setStringSet(StudyIdentifier studyIdentifier, String healthCode, ParticipantOption option, Set<String> value) {
        checkNotNull(studyIdentifier);
        checkArgument(isNotBlank(healthCode));
        checkNotNull(option == ParticipantOption.DATA_GROUPS);
        
        AccountId accountId = AccountId.forHealthCode(studyIdentifier.getIdentifier(), healthCode);
        Account account = accountDao.getAccount(accountId);
        if (account != null) {
            account.setDataGroups(value);
            accountDao.updateAccount(account, false);
        }
    }

    /**
     * Persist a string set option with a set of keys that are ordered by their insertion in the set.
     */
    public void setOrderedStringSet(StudyIdentifier studyIdentifier, String healthCode, ParticipantOption option, LinkedHashSet<String> value) {
        checkNotNull(studyIdentifier);
        checkArgument(isNotBlank(healthCode));
        checkNotNull(option == ParticipantOption.LANGUAGES);
        
        AccountId accountId = AccountId.forHealthCode(studyIdentifier.getIdentifier(), healthCode);
        Account account = accountDao.getAccount(accountId);
        if (account != null) {
            account.setLanguages(value);
            accountDao.updateAccount(account, false);
        }
    }
    
    public void setDateTimeZone(StudyIdentifier studyIdentifier, String healthCode, ParticipantOption option, DateTimeZone zone) {
        checkNotNull(studyIdentifier);
        checkArgument(isNotBlank(healthCode));
        checkNotNull(option == ParticipantOption.TIME_ZONE);

        AccountId accountId = AccountId.forHealthCode(studyIdentifier.getIdentifier(), healthCode);
        Account account = accountDao.getAccount(accountId);
        if (account != null) {
            account.setTimeZone(zone);
            accountDao.updateAccount(account, false);
        }
    }
    
    /**
     * Delete the entire record associated with a participant in the study and all options.
     */
    public void deleteAllParticipantOptions(String healthCode) {
        checkArgument(isNotBlank(healthCode));
        
        optionsDao.deleteAllOptions(healthCode);
    }
}
