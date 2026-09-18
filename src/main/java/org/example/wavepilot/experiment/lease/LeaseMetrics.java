package org.example.wavepilot.experiment.lease;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Lease/claim metrics; low-cardinality outcome tags only. */
@Component
public class LeaseMetrics {
    private final Counter claimsWon;
    private final Counter claimsLost;
    private final Counter expiredRequeued;
    private final Counter expiredUncertain;
    private final Counter expiredRebuilt;

    public LeaseMetrics(MeterRegistry registry) {
        this.claimsWon = claim(registry, "won");
        this.claimsLost = claim(registry, "lost");
        this.expiredRequeued = Counter.builder("wavepilot.lease.expired")
                .description("Jobs whose expired lease ended in a safe re-queue")
                .tag("outcome", "requeued").register(registry);
        this.expiredUncertain = Counter.builder("wavepilot.lease.expired")
                .description("Jobs whose expired lease ended in UNCERTAIN (possible external side effects)")
                .tag("outcome", "uncertain").register(registry);
        this.expiredRebuilt = Counter.builder("wavepilot.lease.expired")
                .description("Jobs whose expired lease ended in a rebuild from validated artifacts")
                .tag("outcome", "rebuilt").register(registry);
    }

    private static Counter claim(MeterRegistry registry, String result) {
        return Counter.builder("wavepilot.lease.claims")
                .description("Worker claim attempts by outcome")
                .tag("result", result).register(registry);
    }

    public void claimWon() { claimsWon.increment(); }
    public void claimLost() { claimsLost.increment(); }
    public void expiredRequeued() { expiredRequeued.increment(); }
    public void expiredUncertain() { expiredUncertain.increment(); }
    public void expiredRebuilt() { expiredRebuilt.increment(); }
}
