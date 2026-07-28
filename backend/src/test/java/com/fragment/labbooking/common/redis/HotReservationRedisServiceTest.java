package com.fragment.labbooking.common.redis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ResourceSlotMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HotReservationRedisServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock initLock;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private ResourceSlotMapper resourceSlotMapper;

    private HotReservationRedisService service;

    @BeforeEach
    void setUp() {
        initTableInfo(Reservation.class);
        service = new HotReservationRedisService(
                redisTemplate, redissonClient, reservationMapper, resourceSlotMapper,
                true, 1000, 24
        );
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void preheatedFastPathShouldReserveWithoutDatabaseRead() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        assertThat(service.reserveIfPreheated(3L, 11L, 7L)).isTrue();

        verifyNoInteractions(redissonClient, reservationMapper, resourceSlotMapper);
    }

    @Test
    void missingSnapshotShouldReturnFalseAndAllowDatabaseFallback() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(3L);

        assertThat(service.reserveIfPreheated(3L, 11L, 7L)).isFalse();

        verifyNoInteractions(redissonClient, reservationMapper, resourceSlotMapper);
    }

    @Test
    void preheatedFastPathShouldRejectDuplicateWithoutDatabaseRead() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(2L);

        assertThatThrownBy(() -> service.reserveIfPreheated(3L, 11L, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode()).isEqualTo(409));

        verifyNoInteractions(redissonClient, reservationMapper, resourceSlotMapper);
    }

    @Test
    void startupPreheatShouldInitializeAtomicallyUnderWatchdogLock() throws InterruptedException {
        ResourceSlot slot = hotSlot(12L);
        Reservation booked = new Reservation();
        booked.setUserId(88L);
        when(redisTemplate.hasKey("reservation:hot:snapshot:12"))
                .thenReturn(false, false);
        when(resourceSlotMapper.selectList(any())).thenReturn(List.of(slot));
        when(redissonClient.getLock("lock:reservation:hot:init:12")).thenReturn(initLock);
        when(initLock.tryLock(1000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(initLock.isHeldByCurrentThread()).thenReturn(true);
        when(reservationMapper.selectList(any())).thenReturn(List.of(booked));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        service.preheatOpenHotSlots();

        verify(initLock).unlock();
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate)
                .execute(any(RedisScript.class), anyList(), arguments.capture());
        Object[] initialization = arguments.getAllValues().stream()
                .filter(items -> items.length == 7)
                .findFirst()
                .orElseThrow();
        assertThat(initialization[0]).isEqualTo("3");
        assertThat(initialization[4]).isEqualTo("5");
        assertThat(Long.parseLong((String) initialization[5])).isPositive();
        assertThat(initialization[6]).isEqualTo("88");
    }

    @Test
    void requestCacheMissShouldFailClosedWithoutLockOrDatabaseRebuild() {
        ResourceSlot slot = hotSlot(13L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(3L);

        assertThatThrownBy(() -> service.reserveAndRegisterRollback(slot, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode()).isEqualTo(503));

        verifyNoInteractions(redissonClient, reservationMapper);
    }

    @Test
    void invalidationInsideTransactionShouldRunOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.invalidateSlotCache(16L);

        verify(redisTemplate, never()).delete(anyList());
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.get(0).afterCommit();

        verify(redisTemplate).delete(List.of(
                "reservation:hot:snapshot:16",
                "reservation:hot:stock:16",
                "reservation:hot:users:16"
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
