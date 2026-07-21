package com.fragment.labbooking.knowledge.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Value("${app.knowledge.storage.minio.endpoint:http://127.0.0.1:9000}")
    private String endpoint;

    @Value("${app.knowledge.storage.minio.access-key:minioadmin}")
    private String accessKey;

    @Value("${app.knowledge.storage.minio.secret-key:minioadmin}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
