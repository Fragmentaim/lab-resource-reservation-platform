package com.fragment.labbooking.common.redis;

import com.fragment.labbooking.vo.ResourceVO;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * Cache boundary for the public resource catalog.
 *
 * <p>Only the unfiltered home-page list is cached. Resource details and slot
 * availability are intentionally read from MySQL because they either change
 * frequently or are not hot enough to justify another consistency boundary.</p>
 */
@Component
public class ResourceCatalogCache {

    public static final String RESOURCE_HOME_CACHE = "resourceHome";

    @Cacheable(cacheNames = RESOURCE_HOME_CACHE, key = "'all'", sync = true)
    public List<ResourceVO> getHomeResourceList(Supplier<List<ResourceVO>> loader) {
        return loader.get();
    }

    @CacheEvict(cacheNames = RESOURCE_HOME_CACHE, allEntries = true)
    public void invalidateHomeResourceList() {
        // Spring Cache performs the eviction after this method returns.
    }
}
