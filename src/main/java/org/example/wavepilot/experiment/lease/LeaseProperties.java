package org.example.wavepilot.experiment.lease;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Worker lease tuning. A healthy worker heartbeats several times per lease, so expiry
 * means the node is dead (or partitioned from MySQL) rather than merely slow.
 */
@ConfigurationProperties(prefix = "wavepilot.lease")
public record LeaseProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60s") Duration duration,
        @DefaultValue("100") int scanBatch,
        @DefaultValue("5s") Duration scanGrace,
        /**
         * Runner types whose re-execution has no external side effects (the default mock
         * runner). Only these may be re-queued after a lease expiry once submission may
         * have happened; anything else becomes UNCERTAIN. The default is applied in the
         * compact constructor because @DefaultValue does not bind collections reliably
         * in Boot 3.2 constructor binding.
         */
        List<String> safeRequeueRunnerTypes) {

    public LeaseProperties {
        if (safeRequeueRunnerTypes == null || safeRequeueRunnerTypes.isEmpty()) {
            safeRequeueRunnerTypes = List.of("mock");
        }
    }
}
