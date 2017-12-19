package org.sagebionetworks.bridge.cache;

import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.redis.RedisKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;

public class ViewCache {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewCache.class);
    
    private static final Joiner COLON_JOINER = Joiner.on(":");
    
    public final class ViewCacheKey<T> {
        private final String key;
        public ViewCacheKey(String key) {
            this.key = key;
        }
        String getKey() {
            return key;
        }
    };
    
    private CacheProvider cache;
    private ObjectMapper objectMapper;
    private int cachePeriod;
    
    public final void setCacheProvider(CacheProvider cacheProvider) {
        this.cache = cacheProvider;
    }
    
    public final void setObjectMapper(ObjectMapper mapper) {
        this.objectMapper = mapper;
    }
    
    public final void setCachePeriod(int cachePeriod) {
        this.cachePeriod = cachePeriod;
    }
    
    /**
     * Get the JSON for the viewCacheKey, or if nothing has been cached, call the supplier, 
     * cache the JSON representation of the object returned, and return that JSON.
     * @param key
     * @param supplier
     * @return
     */
    public <T> String getView(ViewCacheKey<T> key, Supplier<T> supplier) {
        try {
            String value = cache.getObject(key.getKey(), String.class);
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
    public <T> void removeView(ViewCacheKey<T> key) {
        logger.debug("Deleting JSON for '" +key.getKey() +"'");
        cache.removeObject(key.getKey());
    }
    
    /**
     * Create a viewCacheKey for a particular type of entity, and the set of identifiers 
     * that will identify that entity.
     * @param clazz
     * @param identifiers
     * @return
     */
    public <T> ViewCacheKey<T> getCacheKey(Class<T> clazz, String... identifiers) {
        String id = COLON_JOINER.join(identifiers);
        return new ViewCacheKey<T>(RedisKey.VIEW.getRedisKey(id + ":" + clazz.getSimpleName()));
    }
    
    private <T> String cacheView(ViewCacheKey<T> key, Supplier<T> supplier) throws JsonProcessingException {
        logger.debug("Caching JSON for " +key.getKey()+"'");
        T object = supplier.get();
        String value = objectMapper.writeValueAsString(object);
        cache.setObject(key.getKey(), value, cachePeriod);
        return value;
    }
    
}
