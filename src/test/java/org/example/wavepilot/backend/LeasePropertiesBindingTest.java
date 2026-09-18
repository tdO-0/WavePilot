package org.example.wavepilot.backend;

import org.example.wavepilot.experiment.lease.LeaseConfiguration;
import org.example.wavepilot.experiment.lease.LeaseProperties;
import org.example.wavepilot.experiment.outbox.OutboxProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the @ConfigurationProperties records against a Boot 3.2 pitfall: a no-arg
 * constructor on the record silently disables constructor binding, so command-line and
 * environment overrides (e.g. the UNCERTAIN/requeue whitelist) would be ignored.
 */
class LeasePropertiesBindingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxProperties.class)
    static class OutboxPropertiesRegistration {
    }

    @Test
    void leasePropertiesBindFromEnvironmentAndDefaultTheSafeRunnerList() {
        new ApplicationContextRunner()
                .withPropertyValues("wavepilot.lease.enabled=false", "wavepilot.lease.scan-grace=0s",
                        "wavepilot.lease.safe-requeue-runner-types=unsafe-none")
                .withUserConfiguration(LeaseConfiguration.class)
                .run(ctx -> {
                    var lease = ctx.getBean(LeaseProperties.class);
                    assertFalse(lease.enabled(), "enabled must bind");
                    assertEquals(Duration.ZERO, lease.scanGrace(), "scan-grace must bind");
                    assertEquals(List.of("unsafe-none"), lease.safeRequeueRunnerTypes(),
                            "safe-requeue-runner-types must bind");
                });
        new ApplicationContextRunner()
                .withUserConfiguration(LeaseConfiguration.class)
                .run(ctx -> {
                    var lease = ctx.getBean(LeaseProperties.class);
                    assertTrue(lease.enabled());
                    assertEquals(List.of("mock"), lease.safeRequeueRunnerTypes());
                    assertEquals(Duration.ofSeconds(60), lease.duration());
                });
    }

    @Test
    void outboxPropertiesBindFromEnvironment() {
        new ApplicationContextRunner()
                .withPropertyValues("wavepilot.outbox.relay-enabled=false", "wavepilot.outbox.batch-size=7",
                        "wavepilot.outbox.backoff-cap=90s")
                .withUserConfiguration(OutboxPropertiesRegistration.class)
                .run(ctx -> {
                    var outbox = ctx.getBean(OutboxProperties.class);
                    assertFalse(outbox.relayEnabled(), "relay-enabled must bind");
                    assertEquals(7, outbox.batchSize());
                    assertEquals(Duration.ofSeconds(90), outbox.backoffCap());
                });
    }
}
