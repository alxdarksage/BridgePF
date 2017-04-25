package org.sagebionetworks.bridge.okta;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import javax.annotation.Resource;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.crypto.BridgeEncryptor;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.models.PagedResourceList;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.AccountSummary;
import org.sagebionetworks.bridge.models.accounts.Email;
import org.sagebionetworks.bridge.models.accounts.EmailVerification;
import org.sagebionetworks.bridge.models.accounts.HealthId;
import org.sagebionetworks.bridge.models.accounts.PasswordReset;
import org.sagebionetworks.bridge.models.accounts.SignIn;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.subpopulations.Subpopulation;
import org.sagebionetworks.bridge.models.subpopulations.SubpopulationGuid;
import org.sagebionetworks.bridge.services.HealthCodeService;
import org.sagebionetworks.bridge.services.StudyService;
import org.sagebionetworks.bridge.services.SubpopulationService;
import org.sagebionetworks.bridge.util.BridgeCollectors;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.okta.sdk.clients.AuthApiClient;
import com.okta.sdk.clients.UserApiClient;
import com.okta.sdk.exceptions.ApiException;
import com.okta.sdk.framework.ApiClientConfiguration;
import com.okta.sdk.framework.FilterBuilder;
import com.okta.sdk.framework.PagedResults;
import com.okta.sdk.models.auth.AuthResult;
import com.okta.sdk.models.users.LoginCredentials;
import com.okta.sdk.models.users.Password;
import com.okta.sdk.models.users.User;
import com.okta.sdk.models.users.UserProfile;

/**
 * The following template variables now exist for the emails that are sent:
 * ${resetPasswordLink} or ${activationLink}
 * ${org.name}
 * ${user.firstName}
 * ${user.login} (aka the user's email)
 */
@Component("oktaAccountDao")
public class OktaAccountDao implements AccountDao {
    
    private static Logger LOG = LoggerFactory.getLogger(OktaAccountDao.class);
    
    private ApiClientConfiguration config;
    private UserApiClient userApiClient;
    private AuthApiClient authApiClient;
    private StudyService studyService;
    private SubpopulationService subpopService;
    private HealthCodeService healthCodeService;
    private SortedMap<Integer, BridgeEncryptor> encryptors = Maps.newTreeMap();
    
    @Autowired
    final void setStudyService(StudyService studyService) {
        this.studyService = studyService;
    }
    @Autowired
    final void setSubpopulationService(SubpopulationService subpopService) {
        this.subpopService = subpopService;
    }
    @Autowired
    final void setHealthCodeService(HealthCodeService healthCodeService) {
        this.healthCodeService = healthCodeService;
    }
    @Resource(name="encryptorList")
    final void setEncryptors(List<BridgeEncryptor> list) {
        for (BridgeEncryptor encryptor : list) {
            encryptors.put(encryptor.getVersion(), encryptor);
        }
    }
    
    public OktaAccountDao(){
        // TODO: The URL is an organization URL, I believe. We'll need to construct and cache a 
        // client for each study, use Google's cache implementation probably.
        config = new ApiClientConfiguration("https://dev-578886.oktapreview.com",
                "007ouLzhcBwKCG-9qcwYteoe4dsdOT5LblQM0uZ_Ej"); // dev-alxdark
        userApiClient = new UserApiClient(config);
        authApiClient = new AuthApiClient(config);
        
        // TODO: We need to set a User-Agent header
        // TODO: They use link headers for pagination... not sure how their Java client handles this
        // TODO: There are rate limiting headers... again not sure how headers are exposed
        // Assuming the org policy would be "Password Policy" for all orgs we create
        // We are using Okta as a trusted organization, which means we provide an API key
    }

    @Override
    public void verifyEmail(EmailVerification verification) {
        // TODO Currently called because we send users back to a verification page we control. Use Okta in a first pass.
    }

    @Override
    public void resendEmailVerificationToken(StudyIdentifier studyIdentifier, Email email) {
        // TODO See above
    }

    // TODO: This sends you to a page where you have to answer a challenge question which no one has.
    @Override
    public void requestResetPassword(Study study, Email email) {
        try {
            User user = getUserByEmail(email.getEmail());
            if (user != null) {
                userApiClient.forgotPassword(user.getId(), true);    
            }
        } catch(IOException e) {
            rethrowException(e, null);
        }
    }

    @Override
    public void resetPassword(PasswordReset passwordReset) {
        checkNotNull(passwordReset);
        try {
            // Throws 401 if it fails.
            authApiClient.validateRecoveryToken(passwordReset.getSptoken());
            authApiClient.resetPassword(passwordReset.getSptoken(), null, passwordReset.getPassword());
        } catch(IOException e) {
            rethrowException(e, null);
        }
    }
    
    @Override
    public void changePassword(Account account, String newPassword) {
        // TODO: Do not see a way to do this without knowing the old password. The code below probably
        // doesn't work.
        Password password = new Password();
        password.setValue(newPassword);
        
        User user = ((OktaAccount)account).getUser();
        LoginCredentials credentials = user.getCredentials();
        credentials.setPassword(password);
        try {
            User update = userApiClient.updateUser(user);
            ((OktaAccount)account).setUser(update);
        } catch(IOException e) {
            rethrowException(e, null);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Account authenticate(Study study, SignIn signIn) {
        Account account = null;
        try {
            AuthResult result = authApiClient.authenticate(signIn.getEmail(), signIn.getPassword(), null);
            
            Map<String,Object> userMap = (Map<String,Object>)result.getEmbedded().get("user");
            String userId = (String)userMap.get("id");
            User user = userApiClient.getUser(userId);
            
            account = constructAccount(study.getStudyIdentifier(), user);
        } catch(IOException e) {
            rethrowException(e, null);
        }
        return account;
    }

    @Override
    public Account constructAccount(Study study, String email, String password) {
        checkNotNull(study);
        checkArgument(isNotBlank(email));
        checkArgument(isNotBlank(password));
        
        List<SubpopulationGuid> subpopGuids = getSubpopulationGuids(study);

        Password passwordObj = new Password();
        passwordObj.setValue(password);
        
        UserProfile profile = new UserProfile();
        profile.setEmail(email);
        profile.setLogin(email);

        LoginCredentials credentials = new LoginCredentials();
        credentials.setPassword(passwordObj);
        
        User user = new User();
        user.setProfile(profile);
        user.setCredentials(credentials);
        
        Account account = new OktaAccount(study.getStudyIdentifier(), subpopGuids, user, profile, encryptors);
        
        HealthId healthId = healthCodeService.createMapping(study);
        account.setHealthId(healthId);
        
        return account;        
    }

    @Override
    public void createAccount(Study study, Account account, boolean suppressEmail) {
        checkNotNull(study);
        checkNotNull(account);
        try {

            User user = ((OktaAccount)account).getUser();
            User response = userApiClient.createUser(user, false);
            ((OktaAccount)account).setUser(response);
            // TODO: This does not send an email
            
        } catch(IOException e) {
            rethrowException(e, account.getId());
        }        
    }

    @Override
    public void updateAccount(Account account) {
        checkNotNull(account);
        User user = ((OktaAccount)account).getUser();
        try {
            userApiClient.updateUser(user);
        } catch(IOException e) {
            rethrowException(e, account.getId());
        }
    }

    @Override
    public Account getAccount(Study study, String id) {
        checkNotNull(study);
        checkNotNull(isNotBlank(id));
        Account account = null;
        try {
            User user = userApiClient.getUser(id);
            account = constructAccount(study.getStudyIdentifier(), user);
        } catch(IOException e) {
            rethrowException(e, id);
        }
        return account;
    }

    @Override
    public Account getAccountWithEmail(Study study, String email) {
        checkNotNull(study);
        checkNotNull(isNotBlank(email));
        Account account = null;
        try {
            User user = getUserByEmail(email);
            if (user != null) {
                account = constructAccount(study.getStudyIdentifier(), user);    
            }
        } catch(IOException e) {
            rethrowException(e, email);
        }
        return account;
    }

    @Override
    public void deleteAccount(Study study, String email) {
        checkNotNull(study);
        checkNotNull(isNotBlank(email));
        try {
            User user = getUserByEmail(email);
            if (user != null) {
                userApiClient.deactivateUser(user.getId());
                userApiClient.deleteUser(user.getId());
            }
        } catch(IOException e) {
            rethrowException(e, email);
        }
    }

    @Override
    public Iterator<AccountSummary> getAllAccounts() {
        Iterator<AccountSummary> combinedIterator = null;
        for (Study study : studyService.getStudies()) {
            Iterator<AccountSummary> studyIterator = getStudyAccounts(study);
            if (combinedIterator ==  null) {
                combinedIterator = studyIterator;
            } else {
                combinedIterator = Iterators.concat(combinedIterator, studyIterator);    
            }
        }
        return combinedIterator;
    }

    @Override
    public Iterator<AccountSummary> getStudyAccounts(Study study) {
        checkNotNull(study);
        return null;
    }

    @Override
    public PagedResourceList<AccountSummary> getPagedAccountSummaries(Study study, int offsetBy, int pageSize,
            String emailFilter, DateTime startDate, DateTime endDate) {
        
        return getPagedAccountSummaries(study, Integer.toString(offsetBy), pageSize, emailFilter, startDate, endDate);
    }

    // Note that we have to change this API so that offsetBy is a string
    public PagedResourceList<AccountSummary> getPagedAccountSummaries(Study study, String offsetBy, int pageSize,
            String emailFilter, DateTime startDate, DateTime endDate) {
        try {
            FilterBuilder filter = new FilterBuilder();
            if (emailFilter != null) {
                filter = filter.where("profile.email").startsWith(emailFilter);
            }
            if (startDate != null) {
                filter = filter.where("user.created").greaterThanOrEqual(startDate);
            }
            if (endDate != null) {
                filter = filter.where("user.created").lessThanOrEqual(endDate);
            }
            
            PagedResults<User> results = userApiClient
                    .getUsersPagedResultsWithAdvancedSearchAndLimitAndAfterCursor(filter, pageSize, offsetBy);

            List<AccountSummary> summaries = Lists.newArrayListWithCapacity(results.getResult().size());
            for (User user : results.getResult()) {
                summaries.add(AccountSummary.create(study.getStudyIdentifier(), user));
            }
            // TODO: You cannot get a total of accounts in a summary.
            PagedResourceList<AccountSummary> page = new PagedResourceList<AccountSummary>(summaries, null, pageSize,
                    summaries.size());
            if (!results.isLastPage()) {
                page.withFilter("offsetBy", results.getNextUrl());
            }
            return page;
            
        } catch(IOException e) {
            rethrowException(e, emailFilter);
        }
        return null;
    }
    
    @Override
    public String getHealthCodeForEmail(Study study, String email) {
        checkNotNull(study);
        checkNotNull(isNotBlank(email));
        try {
            User user = getUserByEmail(email);
            if (user != null) {
                Account account = constructAccount(study.getStudyIdentifier(), user);
                return account.getHealthCode();
            }
        } catch(IOException e) {
            rethrowException(e, email);
        }
        return null;
    }
    
    private User getUserByEmail(String email) throws IOException {
        List<User> users = userApiClient
                .getUsersWithAdvancedSearch(new FilterBuilder().where("profile.email").equalTo(email));
        return (users.isEmpty()) ? null : users.get(0);
    }
    
    private void rethrowException(IOException e, String userId) {
        if (e instanceof ApiException) {
            ApiException ae = (ApiException)e;
            int statusCode = ae.getStatusCode();
            
            LOG.info(String.format("Okta error: %s: %s", statusCode, ae.getMessage()));
            throw new BridgeServiceException(ae.getErrorResponse().getErrorSummary(), statusCode); 
        }
        throw new BridgeServiceException(e);
    }
    
    private Account constructAccount(StudyIdentifier studyId, User user) {
        checkNotNull(studyId);
        checkNotNull(user);
        
        List<SubpopulationGuid> subpopGuids = getSubpopulationGuids(studyId);
        OktaAccount account = new OktaAccount(studyId, subpopGuids, user, user.getProfile(), encryptors);
        
        HealthId healthId = null;
        if (account.getHealthCode() == null) {
            healthId = healthCodeService.getMapping(account.getHealthId());
        }
        if (healthId == null) {
            healthId = healthCodeService.createMapping(studyId);
            account.setHealthId(healthId);
            updateAccount(account);
        } else {
            account.setHealthId(healthId);    
        }
        return account;
    }
    
    private List<SubpopulationGuid> getSubpopulationGuids(StudyIdentifier studyId) {
        return subpopService.getSubpopulations(studyId)
                .stream()
                .map(Subpopulation::getGuid)
                .collect(BridgeCollectors.toImmutableList());
    }    
}
