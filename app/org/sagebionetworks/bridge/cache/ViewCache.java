package org.sagebionetworks.bridge.cache;

import org.sagebionetworks.bridge.BridgeConstants;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Supplier;

@Component
public class ViewCache {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewCache.class);
    
    public final static class ViewCacheKey {
        private final String key;
        public ViewCacheKey(Class<?> clazz, StudyIdentifier studyId, String key) {
            this.key = String.format("%s:%s:%s", key, studyId.getIdentifier(), clazz.getSimpleName());
        }
        public ViewCacheKey(Class<?> clazz, StudyIdentifier studyId, String key1, String key2) {
            this.key = String.format("%s:%s:%s:%s", key1, key2, studyId.getIdentifier(), clazz.getSimpleName());
        }
        String getKey() {
            return key;
        }
    };
    
    private CacheProvider cache;
    
    @Autowired
    public void setCacheProvider(CacheProvider cacheProvider) {
        this.cache = cacheProvider;
    }
    
    /**
     * Get the JSON for the viewCacheKey, or if nothing has been cached, call the supplier, 
     * cache the JSON representation of the object returned, and return that JSON.
     * @param key
     * @param supplier
     * @return
     */
    public <T> String getView(ViewCacheKey key, Supplier<T> supplier) {
        try {
            String value = cache.getString(key.getKey());
            if (value == null) {
                value = cacheView(key, supplier);
            } else {
                logger.debug("Retrieving " +key.getKey()+"' JSON from cache");
            }
            return value;
        } catch(JsonProcessingException e) {
            throw new BridgeServiceException(e);
        }
    }

    /**
     * Remove the JSON for the view represented by the viewCacheKey.
     * @param key
     */
    public <T> void removeView(ViewCacheKey key) {
        logger.debug("Deleting JSON for '" +key.getKey() +"'");
        cache.removeString(key.getKey());
    }
    
    private <T> String cacheView(ViewCacheKey key, Supplier<T> supplier) throws JsonProcessingException {
        logger.debug("Caching JSON for " +key.getKey()+"'");
        T object = supplier.get();
        String value = BridgeObjectMapper.get().writeValueAsString(object);
        cache.setString(key.getKey(), value, BridgeConstants.BRIDGE_VIEW_EXPIRE_IN_SECONDS);
        return value;
    }
    
}
