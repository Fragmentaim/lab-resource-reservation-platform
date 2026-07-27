package com.fragment.labbooking.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fragment.labbooking.common.audit.AdminAuditHelper;
import com.fragment.labbooking.common.redis.ResourceCatalogCache;
import com.fragment.labbooking.dto.ResourceQueryDTO;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ResourceMapper;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.service.SysDictDataService;
import com.fragment.labbooking.vo.ResourceVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceServiceImplTest {

    @Mock private ResourceMapper resourceMapper;
    @Mock private SysDictDataService sysDictDataService;
    @Mock private ResourceSlotService resourceSlotService;
    @Mock private ReservationMapper reservationMapper;
    @Mock private ResourceCatalogCache resourceCatalogCache;
    @Mock private AdminAuditHelper adminAuditHelper;

    private ResourceServiceImpl resourceService;

    @BeforeEach
    void setUp() {
        initTableInfo(Resource.class);
        resourceService = new ResourceServiceImpl();
        ReflectionTestUtils.setField(resourceService, "baseMapper", resourceMapper);
        ReflectionTestUtils.setField(resourceService, "sysDictDataService", sysDictDataService);
        ReflectionTestUtils.setField(resourceService, "resourceSlotService", resourceSlotService);
        ReflectionTestUtils.setField(resourceService, "reservationMapper", reservationMapper);
        ReflectionTestUtils.setField(resourceService, "resourceCatalogCache", resourceCatalogCache);
        ReflectionTestUtils.setField(resourceService, "adminAuditHelper", adminAuditHelper);
    }

    @Test
    void unfilteredSearchUsesOnlyTheHomeCatalogCache() {
        ResourceVO cached = new ResourceVO();
        cached.setId(1L);
        cached.setResourceName("雷达站");
        when(resourceCatalogCache.getHomeResourceList(any())).thenReturn(List.of(cached));

        List<ResourceVO> result = resourceService.search(new ResourceQueryDTO());

        assertThat(result).containsExactly(cached);
        verify(resourceCatalogCache).getHomeResourceList(any(Supplier.class));
        verifyNoInteractions(resourceMapper, sysDictDataService);
    }

    @Test
    void filteredSearchBypassesTheHomeCatalogCache() {
        ResourceQueryDTO query = new ResourceQueryDTO();
        query.setName("雷达");
        Resource resource = new Resource();
        resource.setId(2L);
        resource.setResourceCode("RM-RADAR");
        resource.setResourceName("雷达站");
        resource.setResourceType("FIELD");
        resource.setStatus("AVAILABLE");
        when(resourceMapper.selectList(any())).thenReturn(List.of(resource));
        when(sysDictDataService.getDictLabelMap("resource_type")).thenReturn(Map.of("FIELD", "场地"));
        when(sysDictDataService.getDictLabelMap("resource_status")).thenReturn(Map.of("AVAILABLE", "可预约"));

        List<ResourceVO> result = resourceService.search(query);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getResourceTypeDesc()).isEqualTo("场地");
        verify(resourceMapper).selectList(any());
        verify(resourceCatalogCache, never()).getHomeResourceList(any());
    }

    private void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) != null) {
            return;
        }
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(entityClass.getName());
        TableInfoHelper.initTableInfo(assistant, entityClass);
    }
}
