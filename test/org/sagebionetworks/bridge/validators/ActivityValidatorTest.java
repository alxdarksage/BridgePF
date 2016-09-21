package org.sagebionetworks.bridge.validators;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.Set;

import org.junit.Test;

import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.models.schedules.Activity;

import com.google.common.collect.Sets;

public class ActivityValidatorTest {

    private static final Set<String> EMPTY_TASKS = Collections.emptySet();

    @Test
    public void rejectsWithoutGuid() {
        try {
            Activity activity = new Activity.Builder().withLabel("Label").withPublishedSurvey("identifier", "BBB")
                    .build();
            Validate.entityThrowingException(new ActivityValidator(EMPTY_TASKS), activity);
            fail("Should have thrown exception");
        } catch (InvalidEntityException e) {
            assertEquals("guid cannot be missing, null, or blank", e.getErrors().get("guid").get(0));
        }
    }

    @Test
    public void rejectsWithoutLabel() {
        try {
            Activity activity = new Activity.Builder().withGuid("AAA").withPublishedSurvey("identifier", "BBB").build();
            Validate.entityThrowingException(new ActivityValidator(EMPTY_TASKS), activity);
            fail("Should have thrown exception");
        } catch (InvalidEntityException e) {
            assertEquals("label cannot be missing, null, or blank", e.getErrors().get("label").get(0));
        }
    }

    @Test
    public void surveyWithoutIdentifierIsOk() {
        new Activity.Builder().withGuid("AAA").withLabel("Label").withSurvey(null, "BBB", null).build();
    }

    @Test
    public void rejectsSurveyWithoutGuid() {
        try {
            Activity activity = new Activity.Builder().withGuid("AAA").withLabel("Label")
                    .withSurvey("identifier", null, null).build();
            Validate.entityThrowingException(new ActivityValidator(EMPTY_TASKS), activity);
            fail("Should have thrown exception");
        } catch (InvalidEntityException e) {
            assertEquals("survey.guid cannot be missing, null, or blank", e.getErrors().get("survey.guid").get(0));
        }
    }

    @Test
    public void rejectsTaskWithoutIdentifier() {
        try {
            Activity activity = new Activity.Builder().withGuid("AAA").withLabel("Label").withTask((String) null)
                    .build();
            Validate.entityThrowingException(new ActivityValidator(EMPTY_TASKS), activity);
            fail("Should have thrown exception");
        } catch (InvalidEntityException e) {
            assertEquals("task.identifier cannot be missing, null, or blank",
                    e.getErrors().get("task.identifier").get(0));
        }
    }

    @Test
    public void rejectsTaskIdentifierNotDeclaredForStudy() {
        try {
            Activity activity = new Activity.Builder().withGuid("AAA").withLabel("Label").withTask("foo").build();
            Validate.entityThrowingException(new ActivityValidator(null), activity);
            fail("Should have thrown exception");
        } catch (InvalidEntityException e) {
            assertEquals("task.identifier 'foo' is not in enumeration: <no task identifiers declared>",
                    e.getErrors().get("task.identifier").get(0));
        }
    }

    @Test
    public void rejectsTaskIdentifierNotInList() {
        try {
            Activity activity = new Activity.Builder().withGuid("AAA").withLabel("Label").withTask("foo").build();
            Validate.entityThrowingException(new ActivityValidator(Sets.newHashSet("bar", "baz")), activity);
            fail("Should have thrown exception");
        } catch (InvalidEntityException e) {
            assertEquals("task.identifier 'foo' is not in enumeration: bar, baz.",
                    e.getErrors().get("task.identifier").get(0));
        }
    }

    @Test
    public void declaredTaskIdentifierOK() {
        Activity activity = new Activity.Builder().withLabel("Label").withTask("foo").withGuid("AAA").build();
        Validate.entityThrowingException(new ActivityValidator(Sets.newHashSet("foo")), activity);
    }
}
