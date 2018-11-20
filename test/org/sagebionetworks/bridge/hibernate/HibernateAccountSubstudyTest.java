package org.sagebionetworks.bridge.hibernate;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HibernateAccountSubstudyTest {

    @Test
    public void test() {
        HibernateAccountSubstudy accountSubstudy = new HibernateAccountSubstudy("studyId", "substudyId", "accountId");
        
        // not yet used, but coming very shortly
        accountSubstudy.setExternalId("externalId");
        
        assertEquals("studyId", accountSubstudy.getStudyId());
        assertEquals("substudyId", accountSubstudy.getSubstudyId());
        assertEquals("accountId", accountSubstudy.getAccountId());
        assertEquals("externalId", accountSubstudy.getExternalId());
    }
}