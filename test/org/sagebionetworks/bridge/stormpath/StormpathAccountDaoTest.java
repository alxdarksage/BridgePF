package org.sagebionetworks.bridge.stormpath;

import static java.util.Comparator.comparing;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.Roles.DEVELOPER;
import static org.sagebionetworks.bridge.Roles.TEST_USERS;
import static org.sagebionetworks.bridge.TestConstants.TEST_STUDY_IDENTIFIER;

import java.util.Collections;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Resource;

import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.sagebionetworks.bridge.Roles;
import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.json.DateUtils;
import org.sagebionetworks.bridge.models.PagedResourceList;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.AccountSummary;
import org.sagebionetworks.bridge.models.accounts.Email;
import org.sagebionetworks.bridge.models.accounts.PasswordReset;
import org.sagebionetworks.bridge.models.accounts.SignIn;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.subpopulations.ConsentSignature;
import org.sagebionetworks.bridge.models.subpopulations.Subpopulation;
import org.sagebionetworks.bridge.services.HealthCodeService;
import org.sagebionetworks.bridge.services.StudyService;
import org.sagebionetworks.bridge.services.SubpopulationService;

import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

@ContextConfiguration("classpath:test-context.xml")
@RunWith(SpringJUnit4ClassRunner.class)
public class StormpathAccountDaoTest {

    private static final String PASSWORD = "P4ssword!";
    private static final int DATE_RECORDS_LIMIT = 9;

    @Resource(name="stormpathAccountDao")
    private StormpathAccountDao accountDao;
    
    @Resource
    private StudyService studyService;

    @Resource
    private SubpopulationService subpopService;
    
    @Resource
    private HealthCodeService healthCodeService;
    
    private Study study;
    
    private Subpopulation subpop;
    
    @Before
    public void setUp() {
        study = studyService.getStudy(TEST_STUDY_IDENTIFIER);
        subpop = subpopService.getSubpopulations(study).get(0);
    }
    
    @Test
    public void getStudyAccounts() {
        Iterator<AccountSummary> i = accountDao.getStudyAccounts(study);
        
        // There's always one... the behavior of the iterator is tested separately
        assertTrue(i.hasNext());
    }
    
    @Test
    public void getAllAccounts() {
        Iterator<AccountSummary> i = accountDao.getAllAccounts(); 
        
        // There's always one... the behavior of the iterator is tested separately
        assertTrue(i.hasNext());
    }
    
    @Test
    public void getStudyPagedAccounts() throws Exception {
        List<String> newAccounts = Lists.newArrayList();
        try {
            PagedResourceList<AccountSummary> accounts = accountDao.getPagedAccountSummaries(study, "0", 10, null, null, null);
            
            // Make sure you add 2 records with the "SADT" infix so searching will work and be tested, 
            // and at least 6 records in total so that paging can be tested.
            int totalAccounts = accounts.getTotal();
            int addAccounts = (totalAccounts < 6) ? (6-totalAccounts)+2 : 2;
            
            for (int i=0; i < addAccounts; i++) {
                String random = RandomStringUtils.randomAlphabetic(5);
                String email = "bridge-testing+SADT"+random+"@sagebridge.org";
                StudyParticipant participant = new StudyParticipant.Builder().withEmail(email).withPassword(PASSWORD)
                        .withRoles(Sets.newHashSet(TEST_USERS)).build();
                Account account = accountDao.constructAccount(study, participant.getEmail(), participant.getPassword());
                accountDao.createAccount(study, account, false);
                
                newAccounts.add(account.getId());
            }
            // Fetch only 5 accounts. Empty search string ignored
            accounts = accountDao.getPagedAccountSummaries(study, "0", 5, "", null, null);
            
            // pageSize is respected
            assertEquals(5, accounts.getItems().size());
            
            // offsetBy is advanced
            AccountSummary account1 = accountDao.getPagedAccountSummaries(study, "1", 5, null, null, null).getItems().get(0);
            AccountSummary account2 = accountDao.getPagedAccountSummaries(study, "2", 5, null, null, null).getItems().get(0);
            assertNotNull(account1.getCreatedOn());
            assertNotNull(account2.getCreatedOn());
            assertEquals(accounts.getItems().get(1), account1);
            assertEquals(accounts.getItems().get(2), account2);
            
            // Next page = offset + pageSize
            AccountSummary nextPageAccount = accountDao.getPagedAccountSummaries(study, "5", 5, null, null, null).getItems().get(0);
            assertFalse(accounts.getItems().contains(nextPageAccount));
            
            // This should be beyond the number of users in any API study. Should be empty
            accounts = accountDao.getPagedAccountSummaries(study, "100000", 100, null, null, null);
            assertEquals(0, accounts.getItems().size());
            
            // This should filter down to one of the accounts
            accounts = accountDao.getPagedAccountSummaries(study, "0", 5, "bridgeit@", null, null);
            assertEquals(1, accounts.getItems().size());
            assertEquals("bridgeit@sagebase.org", accounts.getItems().get(0).getEmail());
            
            accounts = accountDao.getPagedAccountSummaries(study, "0", DATE_RECORDS_LIMIT, "bridge-testing+SADT", null, null);
            assertTrue(accounts.getItems().size() > 0);
            for (AccountSummary summary : accounts.getItems()) {
                assertNull(summary.getFirstName());
                assertNull(summary.getLastName());
            }
            
            // Now work with up to 13 accounts (there are at least 6), sort them by createdOn
            accounts = accountDao.getPagedAccountSummaries(study, "0", DATE_RECORDS_LIMIT, null, null, null);
            
            Collections.sort(accounts.getItems(), comparing(AccountSummary::getCreatedOn));
            totalAccounts = accounts.getItems().size();
            int half = totalAccounts/2;
            DateTime middleCreatedOn = accounts.getItems().get(half).getCreatedOn();

            // This returns no accounts. We have to advanced the time because the servers do get out-of-sync with
            // Stormpath's server time.
            accounts = accountDao.getPagedAccountSummaries(study, "0", DATE_RECORDS_LIMIT, null, DateTime.now().plusMinutes(5), null);
            assertEquals(0, accounts.getItems().size());

            // This returns the last half of the accounts
            accounts = accountDao.getPagedAccountSummaries(study, "0", DATE_RECORDS_LIMIT, null, middleCreatedOn, null);

            assertEquals(middleCreatedOn.toString(), accounts.getFilters().get("startDate"));
            for (AccountSummary summary : accounts.getItems()) {
                assertTrue(summary.getCreatedOn().getMillis() >= middleCreatedOn.getMillis());
            }
            
            // This returns the first half of the accounts
            accounts = accountDao.getPagedAccountSummaries(study, "0", DATE_RECORDS_LIMIT, null, null, middleCreatedOn);
            
            assertEquals(middleCreatedOn.toString(), accounts.getFilters().get("endDate"));
            for (AccountSummary summary : accounts.getItems()) {
                assertTrue(summary.getCreatedOn().getMillis() <= middleCreatedOn.getMillis());
            }
        } finally {
            for (String id : newAccounts) {
                accountDao.deleteAccount(study, id);
            }
        }
    }
    
    @Test
    public void returnsNullWhenThereIsNoAccount() {
        Account account = accountDao.getAccount(study, "thisemaildoesntexist@stormpath.com");
        assertNull(account);
    }
    
    @Test
    public void canAuthenticate() {
        String email = TestUtils.makeRandomTestEmail(StormpathAccountDaoTest.class);
        Account account = null;
        try {
            StudyParticipant participant = new StudyParticipant.Builder().withEmail(email).withPassword(PASSWORD)
                        .withRoles(Sets.newHashSet(TEST_USERS)).build();
            account = accountDao.constructAccount(study, participant.getEmail(), participant.getPassword());
            accountDao.createAccount(study, account, false);
            
            account = accountDao.authenticate(study, new SignIn(study.getIdentifier(), email, PASSWORD, null));
            assertEquals(email, account.getEmail());
        } finally {
            if (account != null) {
                accountDao.deleteAccount(study, account.getId());    
            }
        }
    }
    
    @Test
    public void badPasswordReportedAs404() {
        String email = TestUtils.makeRandomTestEmail(StormpathAccountDaoTest.class);
        Account account = null;
        try {
            StudyParticipant participant = new StudyParticipant.Builder().withEmail(email).withPassword(PASSWORD)
                    .withRoles(Sets.newHashSet(TEST_USERS)).build();
            account = accountDao.constructAccount(study, participant.getEmail(), participant.getPassword());
            accountDao.createAccount(study, account, false);
            try {
                accountDao.authenticate(study, new SignIn(study.getIdentifier(), email, "BadPassword", null));
                fail("Should have thrown an exception");
            } catch(EntityNotFoundException e) {
                assertEquals("Account not found.", e.getMessage());
            }
        } finally {
            if (account != null) {
                accountDao.deleteAccount(study, account.getId());    
            }
        }
    }
    
    @Test(expected = EntityNotFoundException.class)
    public void cannotAuthenticate() {
        accountDao.authenticate(study, new SignIn(study.getIdentifier(), "bridge-testing+noone@sagebridge.org", "belgium", null));
    }
    
    @Test
    public void expiredPasswordResetTokenThrowsCorrectException() {
        try {
            accountDao.resetPassword(new PasswordReset("P@ssword`1", "invalid", TestConstants.TEST_STUDY_IDENTIFIER));
            fail("Should have thrown an exception");
        } catch(BadRequestException e) {
            assertEquals("Password reset token has expired (or already been used).", e.getMessage());
        }
    }

    @Test
    public void crudAccount() {
        String email = TestUtils.makeRandomTestEmail(StormpathAccountDaoTest.class);
        Account account = null;
        try {
            // Sign Up
            long signedOn = DateUtils.getCurrentMillisFromEpoch();
            ConsentSignature sig = new ConsentSignature.Builder().withName("Test Test").withBirthdate("1970-01-01")
                    .withSignedOn(signedOn).build();
            
            StudyParticipant participant = new StudyParticipant.Builder().withEmail(email).withPassword(PASSWORD)
                    .withRoles(Sets.newHashSet(DEVELOPER, TEST_USERS)).build();
            account = accountDao.constructAccount(study, participant.getEmail(), participant.getPassword());
            account.setRoles(participant.getRoles());
            accountDao.createAccount(study, account, false);

            assertNull(account.getFirstName()); // defaults are not visible
            assertNull(account.getLastName());
            account.setEmail(email);
            account.setAttribute("phone", "123-456-7890");
            account.getConsentSignatureHistory(subpop.getGuid()).add(sig);
            account.setAttribute("attribute_one", "value of attribute one");
            
            // Update Account
            accountDao.updateAccount(account);
            
            // Retrieve account with ID
            Account newAccount = accountDao.getAccount(study, account.getId());
            assertEqual(signedOn, account, newAccount);

            // Verify that you can get the health code using the email. We still need this for MailChimp.
            String healthCode = accountDao.getHealthCodeForEmail(study, email);
            assertEquals(healthCode, account.getHealthCode());
            
            newAccount.setRoles(EnumSet.of(Roles.DEVELOPER, Roles.RESEARCHER, Roles.WORKER));
            accountDao.updateAccount(newAccount);

            newAccount = accountDao.getAccount(study, account.getId());
            assertEquals(3, newAccount.getRoles().size());

            newAccount.setRoles(EnumSet.of(Roles.TEST_USERS));
            accountDao.updateAccount(newAccount);

            newAccount = accountDao.getAccount(study, account.getId());
            assertEquals(1, newAccount.getRoles().size());
            assertTrue(newAccount.getRoles().contains(Roles.TEST_USERS));

            // finally, test the name
            newAccount.setFirstName("Test");
            newAccount.setLastName("Tester");
            accountDao.updateAccount(newAccount);
            
            newAccount = accountDao.getAccount(study, newAccount.getId());
            assertEquals("Test", newAccount.getFirstName()); // name is now visible
            assertEquals("Tester", newAccount.getLastName());
            
        } finally {
            if (account != null) {
                accountDao.deleteAccount(study, account.getId());
                account = accountDao.getAccount(study, account.getId());
                assertNull(account);
            }
        }
    }

    @Test
    public void canGetHealthCodeGivenEmailAddress() {
        String email = TestUtils.makeRandomTestEmail(StormpathAccountDaoTest.class);
        Account account = null;
        try {
            StudyParticipant participant = new StudyParticipant.Builder().withEmail(email)
                .withPassword(PASSWORD).build();
            account = accountDao.constructAccount(study, participant.getEmail(), participant.getPassword());
            accountDao.createAccount(study, account, false);
            
            // Great... now we should be able to get a healthCode
            String healthCode = accountDao.getHealthCodeForEmail(study, email);
            assertNotNull(healthCode);
            
            assertEquals(healthCode, account.getHealthCode());
        } finally {
            if (account != null) {
                accountDao.deleteAccount(study, account.getId());    
            }
        }
    }
    
    @Test
    public void canResendEmailVerification() throws Exception {
        String email = TestUtils.makeRandomTestEmail(StormpathAccountDaoTest.class);
        StudyParticipant participant = new StudyParticipant.Builder().withEmail(email).withPassword(PASSWORD).build();
        
        Account account = null;
        try {
            account = accountDao.constructAccount(study, participant.getEmail(), participant.getPassword());
            accountDao.createAccount(study, account, false);
            assertNotNull(account.getId());
            
            Email emailObj = new Email(study.getStudyIdentifier(), participant.getEmail());
            accountDao.resendEmailVerificationToken(study.getStudyIdentifier(), emailObj); // now send email
        } finally {
            if (account != null) {
                accountDao.deleteAccount(study, account.getId());    
            }
        }
    }

    private void assertEqual(long signedOn, Account account, Account newAccount) {
        assertNotNull(newAccount.getEmail());
        assertNull(newAccount.getFirstName());
        assertNull(newAccount.getLastName());
        assertEquals(account.getEmail(), newAccount.getEmail());
        assertEquals(account.getAttribute("phone"), newAccount.getAttribute("phone"));
        assertEquals(account.getRoles(), newAccount.getRoles());
        assertEquals(account.getHealthCode(), newAccount.getHealthCode());
        assertEquals(account.getActiveConsentSignature(subpop.getGuid()), 
                newAccount.getActiveConsentSignature(subpop.getGuid()));
        assertEquals(account.getActiveConsentSignature(subpop.getGuid()).getSignedOn(), 
                newAccount.getActiveConsentSignature(subpop.getGuid()).getSignedOn());
        assertEquals(signedOn, newAccount.getActiveConsentSignature(subpop.getGuid()).getSignedOn());
        assertEquals("value of attribute one", account.getAttribute("attribute_one"));
        assertNull(account.getAttribute("attribute_two"));
    }
}
