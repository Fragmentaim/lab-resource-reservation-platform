package com.fragment.labbooking.knowledge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Value("${app.knowledge.ai-service.base-url:http://127.0.0.1:8000}")
    private String baseUrl;

    @Value("${app.knowledge.ai-service.connect-timeout:5000}")
    private int connectTimeout;

    @Value("${app.knowledge.ai-service.read-timeout:120000}")
    private int readTimeout;

    @Bean
    public RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeout));
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Bean
    public RestClient aiServiceRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(baseUrl)
                .build();
    }
}
