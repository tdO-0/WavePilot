package org.example.wavepilot.knowledge.repository;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.TimeUnit;

/** Creates only the WavePilot Milvus connection; schema ownership stays in the repository. */
@Configuration
@EnableConfigurationProperties(MilvusProperties.class)
public class MilvusClientConfiguration {

    private final MilvusProperties properties;
    private MilvusServiceClient client;

    public MilvusClientConfiguration(MilvusProperties properties) {
        this.properties = properties;
    }

    @Bean
    @Lazy
    public synchronized MilvusServiceClient milvusServiceClient() {
        if (client != null) return client;
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(properties.getHost())
                .withPort(properties.getPort())
                .withConnectTimeout(properties.getTimeout(), TimeUnit.MILLISECONDS);
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            builder.withAuthorization(properties.getUsername(), properties.getPassword());
        }
        client = new MilvusServiceClient(builder.build());
        return client;
    }

    @PreDestroy
    public synchronized void close() {
        if (client != null) client.close();
    }
}
