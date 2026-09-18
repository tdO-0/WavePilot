package org.example.wavepilot.experiment.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Tuning knobs for the outbox relay; defaults keep the dev compose responsive. */
@ConfigurationProperties(prefix = "wavepilot.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean relayEnabled,
        @DefaultValue("50") int batchSize,
        @DefaultValue("60s") Duration claimLease,
        @DefaultValue("1s") Duration backoffBase,
        @DefaultValue("300s") Duration backoffCap) {
}
