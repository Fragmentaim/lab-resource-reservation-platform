package com.fragment.labbooking.common.redis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ResourceSlotMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HotReservationRedisServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock HashOperations<String, Object, Object> hashOperations;
    @Mock RedissonClient redissonClient;
    @Mock RLock lock;
    @Mock ReservationMapper reservationMapper;
    @Mock ResourceSlotMapper slotMapper;

    private HotReservationRedisService service;

    @BeforeEach
    void setUp() {
        initTableInfo(Reservation.class);
        service = new HotReservationRedisService(
                redisTemplate, redissonClient, reservationMapper, slotMapper,
                true, 1000, 24, 24);
    }

    @AfterEach
    void cleanTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void sameRequestShouldReturnStoredStateWithoutDatabaseRead() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(10L);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("reservation:request:v2:req-1")).thenReturn(Map.of(
                "requestId", "req-1", "userId", "7", "resourceId", "3", "slotId", "11",
                "status", "PUBLISHED", "expiresAt", "1000", "publishAttempts", "1",
                "lastPublishedAt", "500"
        ));

        var state = service.accept("req-1", 7L, 3L, 11L, 1000);

        assertThat(state.status()).isEqualTo("PUBLISHED");
        assertThat(state.publishAttempts()).isEqualTo(1);
    }

    @Test
    void differentRequestForSameUserShouldBeRejected() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(2L);

        assertThatThrownBy(() -> service.accept("req-2", 7L, 3L, 11L, 1000))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(409));
    }

    @Test
    void missingSnapshotShouldFailClosed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(3L);

        assertThatThrownBy(() -> service.accept("req-3", 7L, 3L, 11L, 1000))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(503));
    }

    @Test
    void redisFailureDuringRoutingShouldAllowCallerToCheckNormalSlotInMysql() {
        when(redisTemplate.hasKey("reservation:hot:v2:snapshot:11"))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThat(service.isPreheatedHotSlot(11L)).isFalse();
    }

    @Test
    void startupShouldPreheatV2Snapshot() throws Exception {
        ResourceSlot slot = hotSlot(12L);
        Reservation booked = new Reservation();
        booked.setUserId(88L);
        when(slotMapper.selectList(any())).thenReturn(List.of(slot));
        when(redisTemplate.hasKey("reservation:hot:v2:snapshot:12")).thenReturn(false, false);
        when(redissonClient.getLock("lock:reservation:hot:v2:init:12")).thenReturn(lock);
        when(lock.tryLock(1000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(reservationMapper.selectList(any())).thenReturn(List.of(booked));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        service.preheatOpenHotSlots();

        verify(lock).unlock();
    }

    @Test
    void invalidationShouldWaitForTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.invalidateSlotCache(16L);

        verify(redisTemplate, never()).delete(anyList());
        List<TransactionSynchronization> callbacks =
                TransactionSynchronizationManager.getSynchronizations();
        callbacks.get(0).afterCommit();
        verify(redisTemplate).delete(List.of(
                "reservation:hot:v2:snapshot:16",
                "reservation:hot:v2:stock:16",
                "reservation:hot:v2:users:16"
        ));
    }

    private ResourceSlot hotSlot(Long id) {
        ResourceSlot slot = new ResourceSlot();
        slot.setId(id);
        slot.setResourceId(3L);
        slot.setSlotType("HOT");
        slot.setStatus("OPEN");
        slot.setOpenTime(LocalDateTime.now().minusMinutes(1));
        slot.setRemainQuota(5);
        slot.setEndDatetime(LocalDateTime.now().plusHours(2));
        return slot;
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
