package org.sagebionetworks.bridge.models.schedules;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.validation.Errors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class CriteriaScheduleStrategy implements ScheduleStrategy {

    public static class ScheduleCriteria {
        private final Schedule schedule;
        private final Integer minAppVersion;
        private final Integer maxAppVersion;
        private final Set<String> allOfGroups;
        private final Set<String> noneOfGroups;

        @JsonCreator
        private ScheduleCriteria(@JsonProperty("schedule") Schedule schedule, @JsonProperty("minAppVersin") Integer minAppVersion, 
                @JsonProperty("maxAppVersion") Integer maxAppVersion, @JsonProperty("allOfGroups") Set<String> allOfGroups, 
                @JsonProperty("noneOfGroups") Set<String> noneOfGroups) {
            this.schedule = schedule;
            this.minAppVersion = minAppVersion;
            this.maxAppVersion = maxAppVersion;
            this.allOfGroups = allOfGroups;
            this.noneOfGroups = noneOfGroups;
        }
        public Schedule getSchedule() {
            return schedule;
        }
        public Integer getMinAppVersion() {
            return minAppVersion;
        }
        public Integer getMaxAppVersion() {
            return maxAppVersion;
        }
        public Set<String> getAllOfGroups() {
            return allOfGroups;
        }
        public Set<String> getNoneOfGroups() {
            return noneOfGroups;
        }
        
        public static class Builder {
            private Schedule schedule;
            private Integer minAppVersion;
            private Integer maxAppVersion;
            private Set<String> allOfGroups = Sets.newHashSet();
            private Set<String> noneOfGroups = Sets.newHashSet();
            
            public Builder withSchedule(Schedule schedule) {
                this.schedule = schedule;
                return this;
            }
            public Builder withMinAppVersion(Integer minAppVersion) {
                this.minAppVersion = minAppVersion;
                return this;
            }
            public Builder withMaxAppVersion(Integer maxAppVersion) {
                this.maxAppVersion = maxAppVersion;
                return this;
            }
            public Builder addRequiredGroup(String... groups) {
                for (String group : groups) {
                    this.allOfGroups.add(group);    
                }
                return this;
            }
            public Builder addProhibitedGroup(String... groups) {
                for (String group : groups) {
                    this.noneOfGroups.add(group);    
                }
                return this;
            }
            public ScheduleCriteria build() {
                return new ScheduleCriteria(schedule, minAppVersion, maxAppVersion, allOfGroups, noneOfGroups);
            }
        }

        @Override
        public int hashCode() {
            return Objects.hash(minAppVersion, maxAppVersion, allOfGroups, noneOfGroups, schedule);
        }
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            ScheduleCriteria other = (ScheduleCriteria) obj;
            return Objects.equals(minAppVersion, other.minAppVersion) && Objects.equals(maxAppVersion, other.maxAppVersion)
                    && Objects.equals(allOfGroups, other.allOfGroups) && Objects.equals(noneOfGroups, other.noneOfGroups) 
                    && Objects.equals(schedule, other.schedule);
        }
        @Override
        public String toString() {
            return "ScheduleCriteria [schedule=" + schedule + ", minAppVersion=" + minAppVersion + ", maxAppVersion="
                    + maxAppVersion + ", allOfGroups=" + allOfGroups + ", noneOfGroups=" + noneOfGroups + "]";
        }
    }
    
    private List<ScheduleCriteria> criteria = Lists.newArrayList();
    
    public void addCriteria(ScheduleCriteria criteria) {
        this.criteria.add(criteria);
    }
    
    public List<ScheduleCriteria> getCriteria() {
        return criteria;
    }
    
    public void setCriteria(List<ScheduleCriteria> criteria) {
        this.criteria = criteria;
    }
    
    @Override
    public Schedule getScheduleForUser(SchedulePlan plan, ScheduleContext context) {
        for (ScheduleCriteria crit : criteria) {
            if (matches(crit, context)){
                return crit.getSchedule();    
            }
        }
        return null;
    }
    
    public boolean matches(ScheduleCriteria crit, ScheduleContext context) {
        Integer appVersion = context.getClientInfo().getAppVersion();
        if (appVersion != null) {
            if (crit.getMinAppVersion() != null && appVersion < crit.getMinAppVersion()) {
                return false;
            }
            if (crit.getMaxAppVersion() != null && appVersion > crit.getMaxAppVersion()) {
                return false;
            }
        }
        
        Set<String> dataGroups = context.getUserDataGroups();
        if (!dataGroups.containsAll(crit.getAllOfGroups())) {
            return false;
        }
        for (String group : crit.getNoneOfGroups()) {
            if (dataGroups.contains(group)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void validate(Set<String> taskIdentifiers, Errors errors) {
        
    }

    @Override
    public List<Schedule> getAllPossibleSchedules() {
        return criteria.stream().map(ScheduleCriteria::getSchedule).collect(Collectors.toList());
    }

    @Override
    public int hashCode() {
        return Objects.hash(criteria);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        CriteriaScheduleStrategy other = (CriteriaScheduleStrategy) obj;
        return Objects.equals(criteria, other.criteria);
    }

    @Override
    public String toString() {
        return "CriteriaScheduleStrategy [criteria=" + criteria + "]";
    }

}
