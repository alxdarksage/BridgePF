package org.sagebionetworks.bridge.okta;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Iterator;

import org.sagebionetworks.bridge.models.accounts.AccountSummary;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;

import com.okta.sdk.models.users.User;

public class OktaAccountIterator implements Iterator<AccountSummary> {
    
    private final StudyIdentifier studyId;
    private final Iterator<User> iterator;
    
    public OktaAccountIterator(StudyIdentifier studyId, Iterator<User> iterator) {
        checkNotNull(iterator);
        
        this.studyId = studyId;
        this.iterator = iterator;
    }
    
    @Override
    public boolean hasNext() {
        return iterator.hasNext();
    }

    @Override
    public AccountSummary next() {
        User acct = iterator.next();
        if (acct != null) {
            return AccountSummary.create(studyId, acct);
        }
        return null;
    }

    @Override
    public void remove() {
        iterator.remove();
    }


}
