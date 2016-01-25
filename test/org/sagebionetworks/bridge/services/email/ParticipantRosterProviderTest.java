package org.sagebionetworks.bridge.services.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.dao.ParticipantOption.SharingScope;
import org.sagebionetworks.bridge.models.accounts.UserProfile;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyParticipant;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class ParticipantRosterProviderTest {
    
    private Study study;

    @Before
    public void setUp() throws Exception {
        study = TestUtils.getValidStudy(ParticipantRosterProviderTest.class);
        study.setUserProfileAttributes(Sets.newHashSet("phone", "recontact"));
    }

    @Test
    public void correctlySplitsRecipients() throws Exception {
        StudyParticipant participant = new StudyParticipant();
        List<StudyParticipant> participants = Lists.newArrayList(participant);
        
        study.setConsentNotificationEmail("bridge-testing@sagebase.org,postmaster@sagebase.org");
        ParticipantRosterProvider provider = new ParticipantRosterProvider(study, participants);
        
        MimeTypeEmail email = provider.getMimeTypeEmail();
        assertEquals(2, email.getRecipientAddresses().size());
        
        Set<String> recipients = Sets.newHashSet("bridge-testing@sagebase.org", "postmaster@sagebase.org");
        assertEquals(recipients, Sets.newHashSet(email.getRecipientAddresses()));
    }
    
    @Test
    public void participantsCorrectlyDescribedInText() {
        StudyParticipant participant = new StudyParticipant();
        participant.setFirstName("First");
        participant.setLastName("Last");
        participant.setEmail("test@test.com");
        participant.put("phone", "(123) 456-7890");
        participant.setNotifyByEmail(Boolean.FALSE);
        participant.put("recontact", "true");
        participant.setHealthCode("AAA");
        List<StudyParticipant> participants = Lists.newArrayList(participant);

        ParticipantRosterProvider provider = new ParticipantRosterProvider(study, participants);
        
        assertEquals("There is 1 user enrolled in this study. Please see the attached TSV file.\n", provider.createInlineParticipantRoster());
        
        StudyParticipant numberTwo = new StudyParticipant();
        numberTwo.setEmail("test2@test.com");
        participants.add(numberTwo);
        
        assertTrue(provider.createInlineParticipantRoster().contains(
            "There are 2 users enrolled in this study. Please see the attached TSV file.\n"));
        
        participants.clear();
        assertTrue(provider.createInlineParticipantRoster().contains(
            "There are no users enrolled in this study.\n"));
    }
    
    @Test
    public void participantsCorrectlyDescribedInCSV() {
        StudyParticipant participant = new StudyParticipant();
        participant.setFirstName("First");
        participant.setLastName("Last");
        participant.setEmail("test@test.com");
        participant.put("phone", "(123)\t456-7890"); // Tab snuck into this string should be converted to a space
        participant.setNotifyByEmail(Boolean.FALSE);
        participant.put("recontact", "false");
        participant.put(UserProfile.SHARING_SCOPE_FIELD, SharingScope.NO_SHARING.name());
        participant.setHealthCode("AAA");
        participant.setExternalId("abc");
        
        // Force the order of these
        participant.setDataGroups(Sets.newHashSet("group1"));
        participant.setSubpopulationNames("Default Consent Group");
        List<StudyParticipant> participants = Lists.newArrayList(participant);

        String headerString = row("Email", "First Name", "Last Name", "Sharing Scope", "Email Notifications", "External ID", "Data Groups", "Phone", "Recontact", "Health Code", "Consent Groups");
        
        ParticipantRosterProvider provider = new ParticipantRosterProvider(study, participants);
        String output = headerString + row("test@test.com", "First", "Last", "Not Sharing", "false", "abc", "group1", "(123) 456-7890", "false", "AAA", "Default Consent Group");
        assertEquals(output, provider.createParticipantTSV());
        
        participant.setLastName(null);
        output = headerString + row("test@test.com","First","","Not Sharing","false","abc","group1","(123) 456-7890","false", "AAA", "Default Consent Group");
        assertEquals(output, provider.createParticipantTSV());
        
        participant.setFirstName(null);
        participant.setLastName("Last");
        output = headerString + row("test@test.com","","Last","Not Sharing","false","abc","group1","(123) 456-7890","false", "AAA", "Default Consent Group");
        assertEquals(output, provider.createParticipantTSV());
        
        participant.setExternalId(null);
        output = headerString + row("test@test.com","","Last","Not Sharing","false","","group1","(123) 456-7890","false", "AAA", "Default Consent Group");
        assertEquals(output, provider.createParticipantTSV());
        
        participant.remove("phone");
        output = headerString + row("test@test.com","","Last","Not Sharing","false","","group1","","false", "AAA", "Default Consent Group");
        assertEquals(output, provider.createParticipantTSV());
        
        participant.remove(UserProfile.SHARING_SCOPE_FIELD);
        output = headerString + row("test@test.com","","Last","","false","","group1","","false","AAA", "Default Consent Group");
        assertEquals(output, provider.createParticipantTSV());
        
        StudyParticipant numberTwo = new StudyParticipant();
        numberTwo.setEmail("test2@test.com");
        
        // This is pretty broken, but you should still get output. 
        participants.add(numberTwo);
        output = headerString + row("test@test.com","","Last","","false","","group1","","false", "AAA", "Default Consent Group") + row("test2@test.com","","","","","","","","","","");
        assertEquals(output, provider.createParticipantTSV());
        
        participants.clear();
        assertEquals(headerString, provider.createParticipantTSV());
    }
    
    @Test
    public void noHealthCodeExportMeansNoColumn() {
        study.setHealthCodeExportEnabled(false);
        
        StudyParticipant participant = new StudyParticipant();
        participant.setFirstName("First");
        participant.setLastName("Last");
        participant.setEmail("test@test.com");
        participant.put("phone", "(123)\t456-7890"); // Tab snuck into this string should be converted to a space
        participant.setNotifyByEmail(Boolean.FALSE);
        participant.setExternalId("abc");
        participant.put("recontact", "false");
        participant.put(UserProfile.SHARING_SCOPE_FIELD, SharingScope.NO_SHARING.name());
        participant.setHealthCode("AAA");
        participant.setSubpopulationNames("Default Consent Group");
        List<StudyParticipant> participants = Lists.newArrayList(participant);

        String headerString = row("Email", "First Name", "Last Name", "Sharing Scope", "Email Notifications", "External ID", "Data Groups", "Phone", "Recontact", "Consent Groups");
        
        ParticipantRosterProvider provider = new ParticipantRosterProvider(study, participants);
        String output = headerString + row("test@test.com", "First", "Last", "Not Sharing", "false", "abc", "", "(123) 456-7890", "false", "Default Consent Group");

        assertEquals(output, provider.createParticipantTSV());
    }
    
    @Test
    public void assemblesCorrectEmail() throws Exception {
        ParticipantRosterProvider provider = new ParticipantRosterProvider(study, Lists.newArrayList());
        MimeTypeEmail email = provider.getMimeTypeEmail();
        
        assertEquals("Study participants for Test Study [ParticipantRosterProviderTest]", email.getSubject());
        assertNull(email.getSenderAddress()); // comes from our default support address
        Set<String> recipients = Sets.newHashSet("bridge-testing+consent@sagebase.org", "bridge-testing+consent@sagebase.org");
        assertEquals(recipients, Sets.newHashSet(email.getRecipientAddresses()));
    }
    
    private String row(String... fields) {
        return Joiner.on("\t").join(fields) + "\n";
    }

}
