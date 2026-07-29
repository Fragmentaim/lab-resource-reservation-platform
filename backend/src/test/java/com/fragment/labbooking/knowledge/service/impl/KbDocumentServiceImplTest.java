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
import com.fragment.labbooking.knowledge.entity.KbChunk;
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
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

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

        Map<Long, String> versions = service.listAccessibleDocumentVersions(user(7L));

        assertThat(versions).containsExactlyInAnyOrderEntriesOf(Map.of(3L, "v1", 9L, "v1"));
        ArgumentCaptor<LambdaQueryWrapper<KbDocument>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(documentMapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
                .contains("chunk_count")
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

    @Test
    void reprocessShouldKeepTheActiveVersionUntilTheNewVersionIsReady() {
        KbDocument document = document(12L, DocumentVisibilityConstants.PUBLIC, 100L);
        document.setDocVersion("v3");
        document.setChunkCount(8);
        document.setParserProvider("docling");
        document.setParseQuality("{\"quality_score\":0.9}");
        when(documentMapper.selectById(12L)).thenReturn(document);
        when(documentMapper.updateById(any(KbDocument.class))).thenReturn(1);
        when(outboxProperties.isEnabled()).thenReturn(true);
        when(documentProcessProperties.isEnabled()).thenReturn(true);

        service.reprocessDocument(12L);

        assertThat(document.getStatus()).isEqualTo(DocumentStatusConstants.PENDING);
        assertThat(document.getDocVersion()).isEqualTo("v3");
        assertThat(document.getChunkCount()).isEqualTo(8);
        assertThat(document.getParserProvider()).isEqualTo("docling");
        assertThat(document.getParseQuality()).contains("quality_score");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPersistChunksInBoundedMultiValueBatches() {
        List<AiServiceClient.ChunkResult> chunks = IntStream.range(0, 201)
                .mapToObj(index -> new AiServiceClient.ChunkResult(
                        "chunk-" + index, index, "content-" + index, 10, 1,
                        "规则", List.of("规则"), "hash-" + index, index * 10,
                        index * 10 + 9, "vector-" + index))
                .toList();
        AiServiceClient.ProcessResult result = new AiServiceClient.ProcessResult(
                chunks.size(), "READY", "v1", List.of(), List.of(), chunks, null);

        ReflectionTestUtils.invokeMethod(service, "saveChunks", 7L, result);

        ArgumentCaptor<List<KbChunk>> batches = ArgumentCaptor.forClass(List.class);
        verify(kbChunkMapper, times(2)).insertBatch(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(200, 1);
        assertThat(batches.getAllValues().get(0).get(0).getChunkUid()).isEqualTo("chunk-0");
        assertThat(batches.getAllValues().get(1).get(0).getChunkUid()).isEqualTo("chunk-200");
    }

    private KbDocument document(Long id, String visibility, Long uploaderId) {
        KbDocument document = new KbDocument();
        document.setId(id);
        document.setTitle("实验室规程");
        document.setFileName("rule.pdf");
        document.setStatus(DocumentStatusConstants.READY);
        document.setDocVersion("v1");
        document.setChunkCount(1);
        document.setVisibility(visibility);
        document.setUploaderId(uploaderId);
        return document;
    }

    private LoginUser user(Long id) {
        return new LoginUser(id, "user" + id, "用户" + id, "USER", null);
    }
}
