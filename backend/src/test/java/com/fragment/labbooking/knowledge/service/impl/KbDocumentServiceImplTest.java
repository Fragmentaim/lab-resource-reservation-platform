package com.fragment.labbooking.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.outbox.MessageOutboxProperties;
import com.fragment.labbooking.common.outbox.MessageOutboxService;
import com.fragment.labbooking.entity.SysUser;
import com.fragment.labbooking.knowledge.common.constants.DocumentStatusConstants;
import com.fragment.labbooking.knowledge.common.constants.DocumentVisibilityConstants;
import com.fragment.labbooking.knowledge.entity.KbDocument;
import com.fragment.labbooking.knowledge.entity.KbDocumentAccess;
import com.fragment.labbooking.knowledge.entity.KbDocumentProcessEvent;
import com.fragment.labbooking.knowledge.mapper.KbChunkMapper;
import com.fragment.labbooking.knowledge.mapper.KbDocumentAccessMapper;
import com.fragment.labbooking.knowledge.mapper.KbDocumentMapper;
import com.fragment.labbooking.knowledge.mapper.KbDocumentProcessEventMapper;
import com.fragment.labbooking.knowledge.mq.DocumentProcessProperties;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.service.MinioService;
import com.fragment.labbooking.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KbDocumentServiceImplTest {

    @Mock private AiServiceClient aiServiceClient;
    @Mock private MinioService minioService;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private KbChunkMapper kbChunkMapper;
    @Mock private KbDocumentAccessMapper accessMapper;
    @Mock private MessageOutboxService outboxService;
    @Mock private MessageOutboxProperties outboxProperties;
    @Mock private DocumentProcessProperties documentProcessProperties;
    @Mock private KbDocumentMapper documentMapper;
    @Mock private KbDocumentProcessEventMapper processEventMapper;

    private KbDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KbDocument.class);
        TableInfoHelper.initTableInfo(assistant, KbDocumentAccess.class);
        TableInfoHelper.initTableInfo(assistant, KbDocumentProcessEvent.class);
        service = new KbDocumentServiceImpl(aiServiceClient, minioService, sysUserMapper, kbChunkMapper,
                accessMapper, processEventMapper, outboxService, outboxProperties, documentProcessProperties,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseMapper", documentMapper);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldFilterRagDocumentIdsByCurrentUserAccessScope() {
        KbDocumentAccess granted = new KbDocumentAccess();
        granted.setDocumentId(9L);
        when(accessMapper.selectList(any())).thenReturn(List.of(granted));

        KbDocument publicDocument = document(3L, DocumentVisibilityConstants.PUBLIC, 100L);
        KbDocument grantedDocument = document(9L, DocumentVisibilityConstants.SPECIFIED_USERS, 100L);
        when(documentMapper.selectList(any())).thenReturn(List.of(publicDocument, grantedDocument));

        List<Long> ids = service.listAccessibleReadyDocumentIds(user(7L));

        assertThat(ids).containsExactly(3L, 9L);
        ArgumentCaptor<LambdaQueryWrapper<KbDocument>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(documentMapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
                .contains("status")
                .contains("visibility")
                .contains("uploader_id");
    }

    @Test
    void shouldRejectSpecifiedDocumentForUserWithoutGrant() {
        KbDocument document = document(11L, DocumentVisibilityConstants.SPECIFIED_USERS, 100L);
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(accessMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.getDocumentDetail(11L, user(7L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问");
    }

    @Test
    void shouldAllowSpecifiedDocumentForGrantedUser() {
        KbDocument document = document(11L, DocumentVisibilityConstants.SPECIFIED_USERS, 100L);
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(accessMapper.selectCount(any())).thenReturn(1L);
        SysUser uploader = new SysUser();
        uploader.setNickname("管理员");
        when(sysUserMapper.selectById(100L)).thenReturn(uploader);

        assertThat(service.getDocumentDetail(11L, user(7L)).getTitle()).isEqualTo("实验室规程");
    }

    @Test
    void shouldExposeOnlyMetadataWhenListingProcessEvents() {
        KbDocument document = document(11L, DocumentVisibilityConstants.PUBLIC, 100L);
        when(documentMapper.selectById(11L)).thenReturn(document);
        KbDocumentProcessEvent event = new KbDocumentProcessEvent();
        event.setStage("COMPLETED");
        event.setStatus("SUCCEEDED");
        event.setMessage("文档已完成处理，可以参与问答");
        event.setDetailJson("{\"chunk_count\":3}");
        when(processEventMapper.selectList(any())).thenReturn(List.of(event));

        var events = service.listProcessEvents(11L, user(7L));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getDetail()).containsEntry("chunk_count", 3);
        assertThat(events.get(0).getMessage()).doesNotContain("实验室规程");
    }

    private KbDocument document(Long id, String visibility, Long uploaderId) {
        KbDocument document = new KbDocument();
        document.setId(id);
        document.setTitle("实验室规程");
        document.setFileName("rule.pdf");
        document.setStatus(DocumentStatusConstants.READY);
        document.setVisibility(visibility);
        document.setUploaderId(uploaderId);
        return document;
    }

    private LoginUser user(Long id) {
        return new LoginUser(id, "user" + id, "用户" + id, "USER", null);
    }
}
