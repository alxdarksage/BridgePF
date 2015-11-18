package org.sagebionetworks.bridge.models.schedules;

import java.util.List;
import java.util.Set;

import org.springframework.validation.Errors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo( use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @Type(name="SimpleScheduleStrategy", value=SimpleScheduleStrategy.class),
    @Type(name="ABTestScheduleStrategy", value=ABTestScheduleStrategy.class)
})
public interface ScheduleStrategy {
    
    /**
     * Get a specific schedule for the given user. This can vary by user, based on the strategy 
     * (the implementation for assigning schedules), however, it needs to be idempotent (each 
     * call for a user must return the same schedule), and it must be possible to enumerate all 
     * possible schedules that can be returned by this strategy.
     * @param plan
     * @param context
     * @return
     */
    public Schedule getScheduleForUser(SchedulePlan plan, ScheduleContext context);
    
    /**
     * Validate that the strategy implementation instance is valid.
     * @param taskIdentifiers
     * @param errors
     */
    public void validate(Set<String> taskIdentifiers, Errors errors);
    
    /**
     * Get all possible schedules that this schedule strategy might schedule. This can be used to 
     * examine or manipulate all the schedules regardless of the strategy implementation. It must 
     * be possible to enumerate all possible schedules that can be returned by this strategy.
     * 
     * @return an immutable list of all possible schedules that can be generated by this strategy.
     */
    @JsonIgnore
    public List<Schedule> getAllPossibleSchedules();

}
