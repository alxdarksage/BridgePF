package org.sagebionetworks.bridge.models.studies;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Significant fields from the study object that are used throughout our code base, 
 * but that don't require loading the full Study object.
 */
@JsonDeserialize(as=StudyIdentifierImpl.class)
public interface StudyIdentifier {
    
    public String getIdentifier();
    public String getResearcherRole();

}
