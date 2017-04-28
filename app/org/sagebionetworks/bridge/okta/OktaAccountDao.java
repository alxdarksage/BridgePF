package org.sagebionetworks.bridge.okta;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.BridgeConstants;
import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.config.BridgeConfigFactory;
import org.sagebionetworks.bridge.crypto.BridgeEncryptor;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.exceptions.AccountDisabledException;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.PagedResourceList;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.AccountStatus;
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
import org.sagebionetworks.bridge.services.SendMailService;
import org.sagebionetworks.bridge.services.StudyService;
import org.sagebionetworks.bridge.services.SubpopulationService;
import org.sagebionetworks.bridge.services.email.BasicEmailProvider;
import org.sagebionetworks.bridge.util.BridgeCollectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.okta.sdk.clients.AuthApiClient;
import com.okta.sdk.clients.UserApiClient;
import com.okta.sdk.exceptions.ApiException;
import com.okta.sdk.framework.ApiClientConfiguration;
import com.okta.sdk.framework.ErrorCause;
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

    private static final Logger LOG = LoggerFactory.getLogger(OktaAccountDao.class);
    private static final String OKTA_DEV_KEY = BridgeConfigFactory.getConfig().get(BridgeConstants.OKTA_DEV_KEY);
    private static final String BASE_URL = BridgeConfigFactory.getConfig().get("webservices.url");
    
    private StudyService studyService;
    private SubpopulationService subpopService;
    private HealthCodeService healthCodeService;
    private CacheProvider cacheProvider;
    private SendMailService sendMailService;
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
    @Autowired
    final void setCacheProvider(CacheProvider cacheProvider) {
        this.cacheProvider = cacheProvider;
    }
    @Autowired
    final void setSendMailService(SendMailService sendMailService) {
        this.sendMailService = sendMailService;
    }
    @Resource(name="encryptorList")
    final void setEncryptors(List<BridgeEncryptor> list) {
        for (BridgeEncryptor encryptor : list) {
            encryptors.put(encryptor.getVersion(), encryptor);
        }
    }
    
    public static class VerificationData {
        private final String studyId;
        private final String userId;
        @JsonCreator
        public VerificationData(@JsonProperty("studyId") String studyId, @JsonProperty("userId") String userId) {
            checkArgument(isNotBlank(studyId));
            checkArgument(isNotBlank(userId));
            this.studyId = studyId;
            this.userId = userId;
        }
        public String getStudyId() {
            return studyId;
        }
        public String getUserId() {
            return userId;
        }
    }
    
    private LoadingCache<String, UserApiClient> userApiClients = CacheBuilder.newBuilder()
            .maximumSize(100).build(new CacheLoader<String, UserApiClient>() {
                public UserApiClient load(String key) {
                    ApiClientConfiguration config = new ApiClientConfiguration(key, OKTA_DEV_KEY); 
                    return new UserApiClient(config);
                }
            });
    private LoadingCache<String, AuthApiClient> authApiClients = CacheBuilder.newBuilder()
            .maximumSize(100).build(new CacheLoader<String, AuthApiClient>() {
                public AuthApiClient load(String key) {
                    ApiClientConfiguration config = new ApiClientConfiguration(key, OKTA_DEV_KEY);
                    return new AuthApiClient(config);
                }
            });
    
    private UserApiClient getUserApiClient(Study study) {
        try {
            study.setOktaOrg("https://dev-578886.oktapreview.com"); // REMOVEME, use study.getOktaOrg()
            return userApiClients.get(study.getOktaOrg());
        } catch (ExecutionException e) {
            throw new BridgeServiceException(e);
        }
    }
    
    private AuthApiClient getAuthApiClient(Study study) {
        try {
            study.setOktaOrg("https://dev-578886.oktapreview.com"); // REMOVEME, use study.getOktaOrg()
            return authApiClients.get(study.getOktaOrg());
        } catch (ExecutionException e) {
            throw new BridgeServiceException(e);
        }
    }
    
    @Override
    public void verifyEmail(EmailVerification verification) {
        checkNotNull(verification);

        VerificationData data = restoreVerification(verification.getSptoken());
        try {
            Study study = studyService.getStudy(data.getStudyId());
            UserApiClient userApiClient = getUserApiClient(study);
            
            User user = userApiClient.getUser(data.getUserId());
            user.getProfile().getUnmapped().put(Account.STATUS, AccountStatus.ENABLED.name());
            userApiClient.updateUser(user);
        } catch (IOException e) {
            rethrowException(e, data.getUserId());
        }
    }

    @Override
    public void resendEmailVerificationToken(StudyIdentifier studyIdentifier, Email email) {
        checkNotNull(studyIdentifier);
        checkNotNull(email);
        
        Study study = studyService.getStudy(studyIdentifier);
        UserApiClient userApiClient = getUserApiClient(study);
        try {
            // TODO: You could argue this isn't necessary... ? If the user doesn't exist, so what?
            User user = getUserByEmailWithoutThrowing(userApiClient, email.getEmail());
            if (user != null) {
                sendEmailVerificationToken(study, user.getId(), email.getEmail());    
            }
        } catch(IOException e) {
            rethrowException(e, null);
        }
    }

    @Override
    public void requestResetPassword(Study study, Email email) {
        checkNotNull(study);
        checkNotNull(email);
        
        UserApiClient userApiClient = getUserApiClient(study); 
        try {
            User user = getUserByEmailWithoutThrowing(userApiClient, email.getEmail());
            if (user != null) {
                String sptoken = BridgeUtils.generateGuid().replaceAll("-", "");
                String cacheKey = sptoken + ":" + study.getIdentifier();
                cacheProvider.setString(cacheKey, email.getEmail(), 60*5);
                
                String studyId = BridgeUtils.encodeURIComponent(study.getIdentifier());
                String url = String.format("%s/mobile/resetPassword.html?study=%s&sptoken=%s",
                        BASE_URL, studyId, sptoken);
                
                BasicEmailProvider provider = new BasicEmailProvider.Builder()
                    .withStudy(study)
                    .withEmailTemplate(study.getResetPasswordTemplate())
                    .withRecipientEmail(email.getEmail())
                    .withToken("url", url).build();
                sendMailService.sendEmail(provider);
            }
        } catch(IOException e) {
            rethrowException(e, null);
        }
    }

    @Override
    public void resetPassword(PasswordReset passwordReset) {
        checkNotNull(passwordReset);
        
        Study study = studyService.getStudy(passwordReset.getStudyIdentifier());
        try {
            String cacheKey = passwordReset.getSptoken() + ":" + study.getIdentifier();
            String email = cacheProvider.getString(cacheKey);
            if (email != null) {
                cacheProvider.removeString(cacheKey);
                
                UserApiClient userApiClient = getUserApiClient(study);
                User user = getUserByEmailWithoutThrowing(userApiClient, email);
                if (user != null) {
                    userApiClient.setPassword(user.getId(), passwordReset.getPassword());    
                }
            }
        } catch(IOException e) {
            rethrowException(e, null);
        }
    }
    
    @Override
    public void changePassword(Account account, String newPassword) {
        checkNotNull(account);
        checkArgument(isNotBlank(newPassword));
        
        Study study = studyService.getStudy(account.getStudyIdentifier());
        UserApiClient userApiClient = getUserApiClient(study);
        try {
            userApiClient.setPassword(account.getId(), newPassword);
        } catch(IOException e) {
            rethrowException(e, null);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Account authenticate(Study study, SignIn signIn) {
        checkNotNull(study);
        checkNotNull(signIn);
        
        UserApiClient userApiClient = getUserApiClient(study);
        AuthApiClient authApiClient = getAuthApiClient(study);
        Account account = null;
        try {
            AuthResult result = authApiClient.authenticate(signIn.getEmail(), signIn.getPassword(), null);
            
            Map<String,Object> userMap = (Map<String,Object>)result.getEmbedded().get("user");
            String userId = (String)userMap.get("id");
            User user = userApiClient.getUser(userId);
            
            account = constructAccount(study.getStudyIdentifier(), user);
            if (account.getStatus() == AccountStatus.DISABLED) {
                throw new AccountDisabledException();
            } else if (account.getStatus() == AccountStatus.UNVERIFIED) {
                throw new EntityNotFoundException(Account.class);
            }
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
    public void createAccount(Study study, Account account, boolean sendVerifyEmail) {
        checkNotNull(study);
        checkNotNull(account);
        
        account.setStatus(sendVerifyEmail ? AccountStatus.UNVERIFIED : AccountStatus.ENABLED);
        UserApiClient userApiClient = getUserApiClient(study);
        try {
            User user = ((OktaAccount)account).getUser();
            User response = userApiClient.createUser(user, true);
            ((OktaAccount)account).setUser(response);
            
            if (sendVerifyEmail) {
                sendEmailVerificationToken(study, response.getId(), response.getProfile().getEmail());    
            }
        } catch(IOException e) {
            rethrowException(e, account.getId());
        }        
    }

    @Override
    public void updateAccount(Account account) {
        checkNotNull(account);
        
        Study study = studyService.getStudy(account.getStudyIdentifier());
        UserApiClient userApiClient = getUserApiClient(study);
        
        try {
            User user = ((OktaAccount)account).getUser();
            User result = userApiClient.updateUser(user);
            ((OktaAccount)account).setUser(result);
        } catch(IOException e) {
            rethrowException(e, account.getId());
        }
    }

    @Override
    public Account getAccount(Study study, String id) {
        checkNotNull(study);
        checkArgument(isNotBlank(id));
        
        UserApiClient userApiClient = getUserApiClient(study);
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
        checkArgument(isNotBlank(email));
        
        UserApiClient userApiClient = getUserApiClient(study);
        Account account = null;
        try {
            User user = getUserByEmailWithoutThrowing(userApiClient, email);
            if (user != null) {
                account = constructAccount(study.getStudyIdentifier(), user);    
            }
        } catch(IOException e) {
            rethrowException(e, email);
        }
        return account;
    }

    @Override
    public void deleteAccount(Study study, String userId) {
        checkNotNull(study);
        checkArgument(isNotBlank(userId));
        
        UserApiClient userApiClient = getUserApiClient(study);
        try {
            User user = userApiClient.getUser(userId);
            if (user != null) {
                userApiClient.deactivateUser(userId);
                userApiClient.deleteUser(userId);
            }
        } catch(IOException e) {
            rethrowException(e, userId);
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
        
        UserApiClient userApiClient = getUserApiClient(study);
        return new OktaAccountIterator(study.getStudyIdentifier(), userApiClient);
    }

    @Override
    public PagedResourceList<AccountSummary> getPagedAccountSummaries(Study study, String offsetBy, int pageSize,
            String emailFilter, DateTime startDate, DateTime endDate) {
        
        UserApiClient userApiClient = getUserApiClient(study);
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
            PagedResults<User> results = null;
            if (isNotBlank(offsetBy)) {
                results = userApiClient.getUsersPagedResultsWithAdvancedSearchAndLimitAndAfterCursor(
                        filter, pageSize, offsetBy);
            } else {
                results = userApiClient.getUsersPagedResultsWithAdvancedSearchAndLimit(filter, pageSize);
            }

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
        checkArgument(isNotBlank(email));
        
        UserApiClient userApiClient = getUserApiClient(study);
        try {
            User user = getUserByEmailWithoutThrowing(userApiClient, email);
            if (user != null) {
                Account account = constructAccount(study.getStudyIdentifier(), user);
                return account.getHealthCode();
            }
        } catch(IOException e) {
            rethrowException(e, email);
        }
        return null;
    }
    
    private void sendEmailVerificationToken(Study study, String userId, String email) {
        checkNotNull(study);
        checkArgument(isNotBlank(userId));
        checkArgument(isNotBlank(email));

        String sptoken = BridgeUtils.generateGuid().replaceAll("-", "");
        
        saveVerification(sptoken, new VerificationData(study.getIdentifier(), userId));
        
        String studyId = BridgeUtils.encodeURIComponent(study.getIdentifier());
        String url = String.format("%s/mobile/verifyEmail.html?study=%s&sptoken=%s",
                BASE_URL, studyId, sptoken);
        
        BasicEmailProvider provider = new BasicEmailProvider.Builder()
            .withStudy(study)
            .withEmailTemplate(study.getVerifyEmailTemplate())
            .withRecipientEmail(email)
            .withToken("url", url).build();
        sendMailService.sendEmail(provider);         
    }
    
    private void saveVerification(String sptoken, VerificationData data) {
        checkArgument(isNotBlank(sptoken));
        checkNotNull(data);
        
        try {
            cacheProvider.setString(sptoken, BridgeObjectMapper.get().writeValueAsString(data), 60*5);
        } catch (IOException e) {
            throw new BridgeServiceException(e);
        }
    }
    
    private VerificationData restoreVerification(String sptoken) {
        checkArgument(isNotBlank(sptoken));
        
        String json = cacheProvider.getString(sptoken);
        if (json != null) {
            try {
                cacheProvider.removeString(sptoken);
                return BridgeObjectMapper.get().readValue(json, VerificationData.class);
            } catch (IOException e) {
                // Suppress. We'll report this as a 404 error.
            }
        }
        throw new BridgeServiceException("Email verification token not found. You may have already enabled this account.", 404);
    }
    
    private User getUserByEmailWithoutThrowing(UserApiClient userApiClient, String email) throws IOException {
        checkNotNull(userApiClient);
        checkNotNull(isNotBlank(email));
        
        FilterBuilder filter = new FilterBuilder().where("profile.login").equalTo(email)
                .and().where("status").equalTo("ACTIVE");
        List<User> users = userApiClient.getUsersWithAdvancedSearch(filter);
        if (users.size() > 1) {
            LOG.warn("Query for email address returned more than one user account: " + email);
        }
        return (users.isEmpty()) ? null : users.get(0);
    }
    
    private void rethrowException(IOException e, String userId) {
        if (e instanceof ApiException) {
            ApiException ae = (ApiException)e;
            int statusCode = ae.getStatusCode();
            
            List<String> errorMessages = ae.getErrorResponse().getErrorCauses().stream()
                    .map(ErrorCause::getErrorSummary).collect(Collectors.toList());
            String details = BridgeUtils.SEMICOLON_SPACE_JOINER.join(errorMessages);
            
            LOG.info(String.format("Okta error: %s: %s", statusCode, ae.getMessage()));
            throw new BridgeServiceException(ae.getErrorResponse().getErrorSummary() + "; " + details, statusCode); 
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
