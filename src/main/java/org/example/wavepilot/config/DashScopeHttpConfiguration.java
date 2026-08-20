package org.example.wavepilot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** Shared HTTP timeout configuration for the WavePilot AI clients. */
@Configuration
public class DashScopeHttpConfiguration {

    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${wavepilot.ai.timeout-millis:180000}") long timeoutMillis) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        return RestClient.builder().requestFactory(requestFactory);
    }
}
