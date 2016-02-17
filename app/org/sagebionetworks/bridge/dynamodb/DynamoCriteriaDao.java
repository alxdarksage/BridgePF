package org.sagebionetworks.bridge.dynamodb;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import javax.annotation.Resource;

import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.dao.CriteriaDao;
import org.sagebionetworks.bridge.models.Criteria;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;

/**
 * This object is optionally associated with models that can be filtered by Criteria, such as 
 * SchedulePlan and Subpopulation. Create and update are combined, and not finding an object does 
 * not throw exceptions (in fact an empty object is returned for read operations).
 */
@Component
public class DynamoCriteriaDao implements CriteriaDao {

    DynamoDBMapper criteriaMapper;
    
    @Resource(name = "criteriaMapper")
    public void setSurveyElementMapper(DynamoDBMapper criteriaMapper) {
        this.criteriaMapper = criteriaMapper;
    }
    
    // Until criteria as an interface is shifted off of SchedulePlan/Subpopulation, 
    // we must copy from an interface to an implementation.
    @Override
    public Criteria copyCriteria(String key, Criteria criteria) {
        DynamoCriteria actualCriteria = new DynamoCriteria();
        actualCriteria.setKey(key);
        if (criteria != null) {
            actualCriteria.setMinAppVersion(criteria.getMinAppVersion());
            actualCriteria.setMaxAppVersion(criteria.getMaxAppVersion());
            actualCriteria.setAllOfGroups(criteria.getAllOfGroups());
            actualCriteria.setNoneOfGroups(criteria.getNoneOfGroups());
        }
        return actualCriteria;        
    }
    
    @Override
    public void createOrUpdateCriteria(Criteria criteria) {
        checkNotNull(criteria);
        checkArgument(isNotBlank(criteria.getKey()));

        Criteria actualCriteria = copyCriteria(criteria.getKey(), criteria);
        criteriaMapper.save(actualCriteria);
    }
    
    @Override
    public Criteria getCriteria(String key) {
        checkArgument(isNotBlank(key));
        
        DynamoCriteria hashKey = new DynamoCriteria();
        hashKey.setKey(key);

        return criteriaMapper.load(hashKey);
    }

    @Override
    public void deleteCriteria(String key) {
        checkArgument(isNotBlank(key));
        
        DynamoCriteria hashKey = new DynamoCriteria();
        hashKey.setKey(key);
        
        Criteria criteria = criteriaMapper.load(hashKey);
        if (criteria != null) {
            criteriaMapper.delete(hashKey);
        }
    }

}