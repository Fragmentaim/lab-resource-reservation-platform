package com.fragment.labbooking.knowledge.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.service.reservation.ReservationCommandService;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.vo.ReservationSubmitVO;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationDraftToolServiceImplTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private RedissonClient redissonClient;
    @Mock private RLock confirmLock;
    @Mock private ResourceService resourceService;
    @Mock private ResourceSlotService resourceSlotService;
    @Mock private ReservationCommandService reservationCommandService;

    private ObjectMapper objectMapper;
    private ReservationDraftToolServiceImpl draftToolService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        draftToolService = new ReservationDraftToolServiceImpl(redisTemplate, redissonClient, objectMapper, resourceService,
                resourceSlotService, reservationCommandService, 600);
    }

    @Test
    void shouldCreateUserBoundDraftWithoutCallingReservationWrite() {
        when(resourceService.getById(11L)).thenReturn(resource());
        when(resourceSlotService.getById(22L)).thenReturn(slot());

        var draft = draftToolService.createDraft(user(7L), 11L, 22L);

        assertThat(draft.getConfirmationToken()).hasSize(32);
        assertThat(draft.isWriteExecuted()).isFalse();
        assertThat(draft.isRequiresUserConfirmation()).isTrue();
        assertThat(draft.getConfirmationEndpoint()).contains(draft.getConfirmationToken());
        verify(valueOperations).set(eq("agent:reservation:draft:" + draft.getConfirmationToken()), anyString(), any());
        verify(reservationCommandService, never()).create(any(), anyString(), any());
    }

    @Test
    void shouldConfirmDraftOnceAndDelegateToAuthoritativeReservationCommand() throws Exception {
        String token = "0123456789abcdef0123456789abcdef";
        String draftJson = objectMapper.writeValueAsString(new DraftJson(7L, 11L, 22L,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(5)));
        when(valueOperations.get("agent:reservation:confirm-result:" + token)).thenReturn(null);
        when(valueOperations.get("agent:reservation:draft:" + token)).thenReturn(draftJson);
        when(redissonClient.getLock("agent:reservation:confirm-lock:" + token)).thenReturn(confirmLock);
        when(confirmLock.tryLock()).thenReturn(true);
        when(confirmLock.isHeldByCurrentThread()).thenReturn(true);
        ReservationSubmitVO submit = new ReservationSubmitVO();
        submit.setReservationNo("RES-100");
        when(reservationCommandService.create(eq(7L), anyString(), any())).thenReturn(submit);

        ReservationSubmitVO result = draftToolService.confirmDraft(user(7L), token);

        assertThat(result.getReservationNo()).isEqualTo("RES-100");
        ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<com.fragment.labbooking.dto.ReservationCreateDTO> dto =
                ArgumentCaptor.forClass(com.fragment.labbooking.dto.ReservationCreateDTO.class);
        verify(reservationCommandService).create(eq(7L), requestId.capture(), dto.capture());
        assertThat(requestId.getValue()).isEqualTo(UUID.nameUUIDFromBytes(
                ("reservation-draft:" + token).getBytes(StandardCharsets.UTF_8)).toString());
        assertThat(dto.getValue().getResourceId()).isEqualTo(11L);
        assertThat(dto.getValue().getSlotId()).isEqualTo(22L);
        verify(redisTemplate).delete("agent:reservation:draft:" + token);
        verify(confirmLock).unlock();
    }

    @Test
    void shouldRejectConfirmationByAnotherUserBeforeReservationWrite() throws Exception {
        String token = "0123456789abcdef0123456789abcdef";
        String draftJson = objectMapper.writeValueAsString(new DraftJson(7L, 11L, 22L,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(5)));
        when(valueOperations.get("agent:reservation:confirm-result:" + token)).thenReturn(null);
        when(valueOperations.get("agent:reservation:draft:" + token)).thenReturn(draftJson);

        assertThatThrownBy(() -> draftToolService.confirmDraft(user(8L), token))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("其他用户");

        verify(reservationCommandService, never()).create(any(), anyString(), any());
    }

    private Resource resource() {
        Resource resource = new Resource();
        resource.setId(11L);
        resource.setResourceName("GPU 服务器");
        resource.setLocation("实验室 A");
        return resource;
    }

    private ResourceSlot slot() {
        ResourceSlot slot = new ResourceSlot();
        slot.setId(22L);
        slot.setResourceId(11L);
        slot.setStatus("OPEN");
        slot.setSlotType("NORMAL");
        slot.setRemainQuota(3);
        slot.setStartDatetime(LocalDateTime.now().plusHours(2));
        slot.setEndDatetime(LocalDateTime.now().plusHours(4));
        return slot;
    }

    private LoginUser user(Long id) {
        return new LoginUser(id, "user" + id, "用户", "USER", "13800000000");
    }

    private record DraftJson(Long userId, Long resourceId, Long slotId,
                             LocalDateTime createdAt, LocalDateTime expiresAt) {
    }
}
