package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.knowledge.service.AiServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiServiceClientImplTest {

    private MockRestServiceServer server;
    private AiServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai-service");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiServiceClientImpl(builder.build());
    }

    @Test
    void shouldDeserializeDocumentProcessingResponseDirectly() {
        server.expect(requestTo("http://ai-service/api/v1/ai/documents/process-by-url"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "chunk_count": 1,
                          "status": "READY",
                          "doc_version": "v3",
                          "chunk_ids": ["chunk-1"],
                          "vector_ids": ["vector-1"],
                          "chunks": [{
                            "chunk_id": "chunk-1",
                            "chunk_index": 0,
                            "content": "雷达站规则",
                            "token_count": 12,
                            "page_no": 8,
                            "section_title": "比赛规则",
                            "title_path": ["规则", "雷达站"],
                            "content_hash": "hash-1",
                            "char_start": 0,
                            "char_end": 6,
                            "vector_id": "vector-1"
                          }],
                          "parse_quality": {
                            "provider": "docling",
                            "provider_version": "2.0",
                            "parse_mode": "hybrid",
                            "unit_count": 10,
                            "quality_score": 0.93,
                            "warnings": ["one scanned page"],
                            "future_field": "ignored"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        AiServiceClient.ProcessResult result = client.processDocumentByUrl(
                10L, "https://files/doc.pdf", "doc.pdf", "pdf", "v3");

        assertThat(result.chunkCount()).isEqualTo(1);
        assertThat(result.chunkIds()).containsExactly("chunk-1");
        assertThat(result.chunks().get(0).titlePath()).containsExactly("规则", "雷达站");
        assertThat(result.parseQuality().provider()).isEqualTo("docling");
        assertThat(result.parseQuality().qualityScore()).isEqualTo(0.93);
        server.verify();
    }

    @Test
    void shouldDeserializeRagSummaryAndDeleteResponsesDirectly() {
        server.expect(requestTo("http://ai-service/api/v1/ai/qa/retrieve"))
                .andRespond(withSuccess("""
                        {"query":"雷达站规则","candidates":[{
                          "chunk_uid":"10:v3:0","document_id":10,"doc_version":"v3",
                          "chunk_index":0,"page_no":8,"title_path":["规则"],
                          "score":0.91,"retrieval_score":0.87,"retrieval_source":"hybrid"
                        }]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ai-service/api/v1/ai/qa/chunks/open"))
                .andRespond(withSuccess("""
                        {"chunks":[{"chunk_uid":"10:v3:0","document_id":10,
                          "doc_version":"v3","content":"完整规则正文","title_path":["规则"]}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ai-service/api/v1/ai/qa/sessions/summarize"))
                .andRespond(withSuccess("""
                        {"summary":"# Agent 会话交接 v1","summary_tokens":32,
                          "provider_usage":{"input_tokens":100,"output_tokens":32}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ai-service/api/v1/ai/documents/10/vectors"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"deleted_count\":3}", MediaType.APPLICATION_JSON));

        AiServiceClient.KnowledgeSearchResult search = client.retrieveKnowledge("雷达站规则", Map.of(10L, "v3"));
        List<AiServiceClient.KnowledgeChunk> chunks = client.openKnowledgeChunks(
                List.of("10:v3:0"), Map.of(10L, "v3"));
        AiServiceClient.SummaryResult summary = client.summarizeSession("", List.of());

        assertThat(search.candidates()).singleElement()
                .extracting(AiServiceClient.KnowledgeCandidate::documentId).isEqualTo(10L);
        assertThat(chunks).singleElement()
                .extracting(AiServiceClient.KnowledgeChunk::content).isEqualTo("完整规则正文");
        assertThat(summary.summaryTokens()).isEqualTo(32);
        assertThat(summary.providerUsage()).containsEntry("input_tokens", 100);
        assertThat(client.deleteDocumentVectors(10L)).isEqualTo(3);
        server.verify();
    }
}
