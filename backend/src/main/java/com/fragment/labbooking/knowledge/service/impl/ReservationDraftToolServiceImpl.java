package com.fragment.labbooking.knowledge.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.dto.ReservationCreateDTO;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.knowledge.service.ReservationDraftToolService;
import com.fragment.labbooking.knowledge.vo.ReservationDraftVO;
import com.fragment.labbooking.service.ReservationService;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.vo.ReservationSubmitVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class ReservationDraftToolServiceImpl implements ReservationDraftToolService {

    private static final String DRAFT_KEY_PREFIX = "agent:reservation:draft:";
    private static final String CONFIRM_LOCK_KEY_PREFIX = "agent:reservation:confirm-lock:";
    private static final String CONFIRM_RESULT_KEY_PREFIX = "agent:reservation:confirm-result:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceService resourceService;
    private final ResourceSlotService resourceSlotService;
    private final ReservationService reservationService;
    private final Duration ttl;

    public ReservationDraftToolServiceImpl(StringRedisTemplate redisTemplate,
                                           ObjectMapper objectMapper,
                                           ResourceService resourceService,
                                           ResourceSlotService resourceSlotService,
                                           ReservationService reservationService,
                                           @Value("${app.knowledge.agent-reservation-confirmation.ttl-seconds:600}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.resourceService = resourceService;
        this.resourceSlotService = resourceSlotService;
        this.reservationService = reservationService;
        this.ttl = Duration.ofSeconds(Math.max(60, ttlSeconds));
    }

    @Override
    public ReservationDraftVO createDraft(LoginUser actor, Long resourceId, Long slotId) {
        Long userId = requireActor(actor);
        if (resourceId == null || slotId == null) {
            throw new BusinessException("资源ID和时段ID不能为空");
        }
        Resource resource = resourceService.getById(resourceId);
        if (resource == null) {
            throw new BusinessException("资源不存在");
        }
        ResourceSlot slot = resourceSlotService.getById(slotId);
        validateDraftable(resource, slot, resourceId);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plus(ttl);
        String token = UUID.randomUUID().toString().replace("-", "");
        DraftPayload payload = new DraftPayload(userId, resourceId, slotId, now, expiresAt);
        write(DRAFT_KEY_PREFIX + token, payload, ttl);

        ReservationDraftVO result = toDraftVO(resource, slot, token, now, expiresAt);
        result.setToolName("reservation_create_draft");
        result.setWriteExecuted(false);
        result.setRequiresUserConfirmation(true);
        result.setConfirmationEndpoint("POST /knowledge/tools/reservation-drafts/" + token + "/confirm");
        result.setNextAction("请向用户展示预约信息；仅在用户点击确认后调用确认接口，系统会重新校验库存、权限与重复预约。");
        return result;
    }

    @Override
    public ReservationSubmitVO confirmDraft(LoginUser actor, String confirmationToken) {
        Long userId = requireActor(actor);
        String token = normalizeToken(confirmationToken);
        ConfirmedPayload completed = read(CONFIRM_RESULT_KEY_PREFIX + token, ConfirmedPayload.class);
        if (completed != null) {
            assertOwner(userId, completed.userId());
            return completed.submit();
        }

        DraftPayload draft = read(DRAFT_KEY_PREFIX + token, DraftPayload.class);
        if (draft == null || draft.expiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("预约草案不存在或已过期，请重新生成确认信息");
        }
        assertOwner(userId, draft.userId());

        Boolean locked = redisTemplate.opsForValue().setIfAbsent(CONFIRM_LOCK_KEY_PREFIX + token, "1", ttl);
        if (!Boolean.TRUE.equals(locked)) {
            completed = read(CONFIRM_RESULT_KEY_PREFIX + token, ConfirmedPayload.class);
            if (completed != null) {
                assertOwner(userId, completed.userId());
                return completed.submit();
            }
            throw new BusinessException(409, "预约确认正在处理中，请稍后查询预约结果");
        }

        try {
            ReservationCreateDTO dto = new ReservationCreateDTO();
            dto.setResourceId(draft.resourceId());
            dto.setSlotId(draft.slotId());
            ReservationSubmitVO submit = reservationService.createReservation(userId, dto);
            write(CONFIRM_RESULT_KEY_PREFIX + token, new ConfirmedPayload(userId, submit), ttl);
            redisTemplate.delete(DRAFT_KEY_PREFIX + token);
            return submit;
        } finally {
            redisTemplate.delete(CONFIRM_LOCK_KEY_PREFIX + token);
        }
    }

    private void validateDraftable(Resource resource, ResourceSlot slot, Long resourceId) {
        if (slot == null) {
            throw new BusinessException("时段不存在");
        }
        if (!resourceId.equals(slot.getResourceId())) {
            throw new BusinessException("时段不属于当前资源");
        }
        if (slot.getStartDatetime() == null || !slot.getStartDatetime().isAfter(LocalDateTime.now())) {
            throw new BusinessException("预约时段已开始或时间无效");
        }
        if (slot.getRemainQuota() == null || slot.getRemainQuota() <= 0) {
            throw new BusinessException("当前时段已无剩余名额");
        }
        if (!"OPEN".equals(slot.getStatus())) {
            throw new BusinessException("当前时段不可预约");
        }
        if (ResourceSlotTypeConstants.HOT.equals(slot.getSlotType())
                && (slot.getOpenTime() == null || LocalDateTime.now().isBefore(slot.getOpenTime()))) {
            throw new BusinessException("热门时段尚未开放预约");
        }
    }

    private ReservationDraftVO toDraftVO(Resource resource, ResourceSlot slot, String token,
                                          LocalDateTime now, LocalDateTime expiresAt) {
        ReservationDraftVO result = new ReservationDraftVO();
        result.setConfirmationToken(token);
        result.setResourceId(resource.getId());
        result.setResourceName(resource.getResourceName());
        result.setLocation(resource.getLocation());
        result.setSlotId(slot.getId());
        result.setStartDatetime(slot.getStartDatetime());
        result.setEndDatetime(slot.getEndDatetime());
        result.setSlotType(slot.getSlotType());
        result.setRemainQuota(slot.getRemainQuota());
        result.setGeneratedAt(now);
        result.setExpiresAt(expiresAt);
        return result;
    }

    private Long requireActor(LoginUser actor) {
        if (actor == null || actor.getId() == null) {
            throw new BusinessException(401, "未登录或登录已失效");
        }
        return actor.getId();
    }

    private void assertOwner(Long actorId, Long draftOwnerId) {
        if (!actorId.equals(draftOwnerId)) {
            throw new BusinessException(403, "无权确认其他用户的预约草案");
        }
    }

    private String normalizeToken(String token) {
        if (!StringUtils.hasText(token) || !token.trim().matches("[0-9a-fA-F]{32}")) {
            throw new BusinessException("预约确认令牌无效");
        }
        return token.trim().toLowerCase();
    }

    private <T> T read(String key, Class<T> type) {
        String raw = redisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("预约确认状态无法读取", exception);
        }
    }

    private void write(String key, Object value, Duration duration) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), duration);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("预约确认状态无法保存", exception);
        }
    }

    private record DraftPayload(Long userId, Long resourceId, Long slotId,
                                LocalDateTime createdAt, LocalDateTime expiresAt) {
    }

    private record ConfirmedPayload(Long userId, ReservationSubmitVO submit) {
    }
}
