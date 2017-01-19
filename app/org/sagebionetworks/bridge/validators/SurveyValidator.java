package org.sagebionetworks.bridge.validators;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.sagebionetworks.bridge.models.surveys.SurveyElementConstants.SURVEY_QUESTION_TYPE;
import static org.sagebionetworks.bridge.models.surveys.SurveyElementConstants.SURVEY_INFO_SCREEN_TYPE;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;

import org.sagebionetworks.bridge.models.surveys.Constraints;
import org.sagebionetworks.bridge.models.surveys.DateConstraints;
import org.sagebionetworks.bridge.models.surveys.DateTimeConstraints;
import org.sagebionetworks.bridge.models.surveys.DecimalConstraints;
import org.sagebionetworks.bridge.models.surveys.Image;
import org.sagebionetworks.bridge.models.surveys.MultiValueConstraints;
import org.sagebionetworks.bridge.models.surveys.NumericalConstraints;
import org.sagebionetworks.bridge.models.surveys.StringConstraints;
import org.sagebionetworks.bridge.models.surveys.Survey;
import org.sagebionetworks.bridge.models.surveys.SurveyElement;
import org.sagebionetworks.bridge.models.surveys.SurveyInfoScreen;
import org.sagebionetworks.bridge.models.surveys.SurveyQuestion;
import org.sagebionetworks.bridge.models.surveys.SurveyQuestionOption;
import org.sagebionetworks.bridge.models.surveys.SurveyRule;
import org.sagebionetworks.bridge.models.surveys.UIHint;
import org.sagebionetworks.bridge.models.surveys.Unit;
import org.sagebionetworks.bridge.upload.UploadUtil;

import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import com.google.common.collect.Sets;

@Component
public class SurveyValidator implements Validator {

    private static final Object[] EMPTY_OBJ_ARG = new Object[]{};
    private static final Set<Unit> HEIGHT_MEASURES = Sets.newHashSet(Unit.METERS, Unit.FEET);
    private static final Set<Unit> WEIGHT_MEASURES = Sets.newHashSet(Unit.POUNDS, Unit.KILOGRAMS);

    @Override
    public boolean supports(Class<?> clazz) {
        return Survey.class.isAssignableFrom(clazz);
    }

    @Override
    public void validate(Object object, Errors errors) {
        Survey survey = (Survey)object;
        if (StringUtils.isBlank(survey.getName())) {
            errors.rejectValue("name", "is required");
        }
        if (StringUtils.isBlank(survey.getIdentifier())) {
            errors.rejectValue("identifier", "is required");
        }
        if (StringUtils.isBlank(survey.getStudyIdentifier())) {
            errors.rejectValue("studyIdentifier", "is required");
        }
        if (StringUtils.isBlank(survey.getGuid())) {
            errors.rejectValue("guid", "is required");
        }
        
        // Validate that no identifier has been duplicated.
        Set<String> foundIdentifiers = Sets.newHashSet();
        for (int i=0; i < survey.getElements().size(); i++) {
            SurveyElement element = survey.getElements().get(i);
            errors.pushNestedPath("elements["+i+"]");
            if (SURVEY_QUESTION_TYPE.equals(element.getType())) {
                doValidateQuestion((SurveyQuestion)element, errors);    
            } else if (SURVEY_INFO_SCREEN_TYPE.equals(element.getType())) {
                doValidateInfoScreen((SurveyInfoScreen)element, errors);
            }
            if (foundIdentifiers.contains(element.getIdentifier())) {
                errors.rejectValue("identifier", "exists in an earlier survey element");
            }
            foundIdentifiers.add(element.getIdentifier());
            errors.popNestedPath();
        }
        // You can get all sorts of NPEs if survey is not valid and you look at the rules.
        // So don't.
        if (!errors.hasErrors()) {
            validateRules(errors, survey.getElements());    
        }
    }
    private void doValidateQuestion(SurveyQuestion question, Errors errors) {
        String questionId = question.getIdentifier();
        if (isBlank(questionId)) {
            errors.rejectValue("identifier", "is required");
        } else if (!UploadUtil.isValidSchemaFieldName(questionId)) {
            errors.rejectValue("identifier", String.format(UploadUtil.INVALID_FIELD_NAME_ERROR_MESSAGE, questionId));
        }

        if (question.getUiHint() == null) {
            errors.rejectValue("uiHint", "is required");
        }
        if (isBlank(question.getPrompt())) {
            errors.rejectValue("prompt", "is required");
        }
        if (question.getConstraints() == null) {
            errors.rejectValue("constraints", "is required");
        } else {
            errors.pushNestedPath("constraints");
            doValidateConstraints(question, question.getConstraints(), errors);
            errors.popNestedPath();
        }
    }
    private void doValidateInfoScreen(SurveyInfoScreen screen, Errors errors) {
        if (isBlank(screen.getIdentifier())) {
            errors.rejectValue("identifier", "is required");
        }
        if (isBlank(screen.getTitle())) {
            errors.rejectValue("title", "is required");
        }
        if (isBlank(screen.getPrompt())) {
            errors.rejectValue("prompt", "is required");
        }
        if (screen.getImage() != null) {
            errors.pushNestedPath("image");
            Image image = screen.getImage();
            if (isBlank(image.getSource())) {
                errors.rejectValue("source", "is required");
            } else if (!image.getSource().startsWith("http://") && !image.getSource().startsWith("https://")) {
                errors.rejectValue("source", "must be a valid URL to an image");
            }
            if (image.getWidth() == 0) {
                errors.rejectValue("width", "is required");
            }
            if (image.getHeight() == 0) {
                errors.rejectValue("height", "is required");
            }
            errors.popNestedPath();
        }
    }
    private void validateRules(Errors errors, List<SurveyElement> elements) {
        // Should not try and back-track in the survey.
        Set<String> alreadySeenIdentifiers = Sets.newHashSet();
        for (int i=0; i < elements.size(); i++) {
            SurveyElement element = elements.get(i);
            if (element instanceof SurveyQuestion) {
                for (SurveyRule rule : ((SurveyQuestion)element).getConstraints().getRules()) {
                    errors.pushNestedPath("elements["+i+"]");
                    // Validate the rule either has a skipTo target, or an endSurvey = TRUE, but not both.
                    if (rule.getSkipToTarget() != null && rule.getEndSurvey() != null) {
                        rejectField(errors, "rule", "cannot have a skipTo target and an endSurvey property");
                    } 
                    // But must have either a skipTo target or an endSurvey property
                    else if (rule.getSkipToTarget() == null && rule.getEndSurvey() == null) {
                        rejectField(errors, "rule", "must have a skipTo target or an endSurvey property");
                    }
                    // Otherwise we can assume there's a skipToTarget, start checking that by looking for back references.
                    else if (alreadySeenIdentifiers.contains(rule.getSkipToTarget())) {
                        rejectField(errors, "rule", "back references question %s", rule.getSkipToTarget());
                    }
                    errors.popNestedPath();
                }
            }
            alreadySeenIdentifiers.add(element.getIdentifier());
        }
        // Now verify that all skipToTarget identifiers actually exist
        for (int i=0; i < elements.size(); i++) {
            SurveyElement element = elements.get(i);
            if (element instanceof SurveyQuestion) {
                for (SurveyRule rule : ((SurveyQuestion)element).getConstraints().getRules()) {
                    // This validation only applies to skipTo target rules.
                    if (rule.getSkipToTarget() != null) {
                        if (!alreadySeenIdentifiers.contains(rule.getSkipToTarget())) {
                            errors.pushNestedPath("elements["+i+"]");
                            rejectField(errors, "rule", "has a skipTo identifier that doesn't exist: %s", rule.getSkipToTarget());
                            errors.popNestedPath();
                        }
                    }
                }
            }
        }
        
    }
    private void doValidateConstraints(SurveyQuestion question, Constraints con, Errors errors) {
        if (con.getDataType() == null) {
            errors.rejectValue("dataType", "is required");
            return;
        }
        UIHint hint = question.getUiHint();
        if (hint == null) {
            return; // will have been validated above, skip this
        }
        if (!con.getSupportedHints().contains(hint)) {
            rejectField(errors, "dataType", "data type '%s' doesn't match the UI hint of '%s'", con.getDataType().name()
                    .toLowerCase(), hint.name().toLowerCase());
        } else if (con instanceof MultiValueConstraints) {
            doValidateConstraintsType(errors, hint, (MultiValueConstraints)con);
        } else if (con instanceof StringConstraints) {
            doValidateConstraintsType(errors, hint, (StringConstraints)con);
        } else if (con instanceof DateConstraints) {
            doValidateConstraintsType(errors, hint, (DateConstraints)con);
        } else if (con instanceof DateTimeConstraints) {
            doValidateConstraintsType(errors, hint, (DateTimeConstraints)con);
        } else if (con instanceof NumericalConstraints) {
            doValidateConstraintsType(errors, hint, (NumericalConstraints)con);
            if (con instanceof DecimalConstraints) {
                doValidateConstraintsType(errors, hint, (DecimalConstraints)con);
            }
        }
    }
    
    private void doValidateConstraintsType(Errors errors, UIHint hint, MultiValueConstraints mcon) {
        String hintName = hint.name().toLowerCase();
        
        if ((mcon.getAllowMultiple() || !mcon.getAllowOther()) && MultiValueConstraints.OTHER_ALWAYS_ALLOWED.contains(hint)) {
            rejectField(errors, "uiHint", "'%s' is only valid when multiple = false and other = true", hintName);
        } else if (mcon.getAllowMultiple() && MultiValueConstraints.ONE_ONLY.contains(hint)) {
            rejectField(errors, "uiHint",
                    "allows multiples but the '%s' UI hint doesn't gather more than one answer", hintName);
        } else if (!mcon.getAllowMultiple() && MultiValueConstraints.MANY_ONLY.contains(hint)) {
            rejectField(errors, "uiHint",
                    "doesn't allow multiples but the '%s' UI hint gathers more than one answer", hintName);
        }

        // must have an enumeration (list of question options)
        List<SurveyQuestionOption> optionList = mcon.getEnumeration();
        if (optionList == null || optionList.isEmpty()) {
            rejectField(errors, "enumeration", "must have non-null, non-empty choices list");
        } else {
            // validate each option
            int numOptions = optionList.size();
            Set<String> valueSet = new HashSet<>();
            Set<String> dupeSet = new TreeSet<>();
            for (int i = 0; i < numOptions; i++) {
                errors.pushNestedPath("enumeration[" + i + "]");

                // must have a label
                SurveyQuestionOption oneOption = optionList.get(i);
                if (StringUtils.isBlank(oneOption.getLabel())) {
                    rejectField(errors, "label", "must be specified");
                }

                String optionValue = oneOption.getValue();
                if (!UploadUtil.isValidAnswerChoice(optionValue)) {
                    errors.rejectValue("value", String.format(UploadUtil.INVALID_ANSWER_CHOICE_ERROR_MESSAGE,
                            optionValue));
                }

                // record values seen so far
                if (!valueSet.contains(optionValue)) {
                    valueSet.add(optionValue);
                } else {
                    dupeSet.add(optionValue);
                }

                errors.popNestedPath();
            }

            // values must be unique
            if (!dupeSet.isEmpty()) {
                rejectField(errors, "enumeration", "must have unique values");
            }
        }
    }
    
    private void doValidateConstraintsType(Errors errors, UIHint hint, StringConstraints con) {
        if (StringUtils.isNotBlank(con.getPattern())) {
            try {
                Pattern.compile(con.getPattern());
            } catch (PatternSyntaxException exception) {
                rejectField(errors, "pattern", "pattern is not a valid regular expression: %s", con.getPattern());
            }
        }
        Integer min = con.getMinLength();
        Integer max = con.getMaxLength();
        if (min != null && max != null) {
            if (min > max) {
                rejectField(errors, "minLength", "is longer than the maxLength");
            }
        }
    }
    
    private void doValidateConstraintsType(Errors errors, UIHint hint, DateConstraints con) {
        LocalDate earliestDate = con.getEarliestValue();
        LocalDate latestDate = con.getLatestValue();
        if (earliestDate != null && latestDate != null) {
            if (latestDate.isBefore(earliestDate)) {
                rejectField(errors, "earliestValue", "is after the latest value");
            }
        }
    }
    
    private void doValidateConstraintsType(Errors errors, UIHint hint, DateTimeConstraints con) {
        DateTime earliestDate = con.getEarliestValue();
        DateTime latestDate = con.getLatestValue();
        if (earliestDate != null && latestDate != null) {
            if (latestDate.isBefore(earliestDate)) {
                rejectField(errors, "earliestValue", "is after the latest value");
            }
        }
    }
    
    private void doValidateConstraintsType(Errors errors, UIHint hint, NumericalConstraints con) {
        Double min = con.getMinValue();
        Double max = con.getMaxValue();
        if (min != null && max != null) {
            if (max < min) {
                rejectField(errors, "minValue", "is greater than the maxValue");
            }
            double diff = max-min;
            if (con.getStep() != null && con.getStep() > diff) {
                rejectField(errors, "step", "is larger than the range of allowable values");
            }
        }
    }
    
    private void doValidateConstraintsType(Errors errors, UIHint hint, DecimalConstraints con) {
        if (hint == UIHint.HEIGHT && !HEIGHT_MEASURES.contains(con.getUnit())) {
            rejectField(errors, "unit", "must be meters or feet for height UI hint");
        } else if (hint == UIHint.WEIGHT && !WEIGHT_MEASURES.contains(con.getUnit())) {
            rejectField(errors, "unit", "must be pounds or kilograms for weight UI hint");
        }
    }
    
    // This is more confusing than helpful.
    private void rejectField(Errors errors, String field, String message, Object... args) {
        if (args != null && args.length > 0) {
            errors.rejectValue(field, message, args, message);    
        } else {
            errors.rejectValue(field, field + " " + message, EMPTY_OBJ_ARG, null);
        }
    }
}
