package org.example.wavepilot.experiment.lease;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registered on every role: ExperimentService always knows the lease window. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LeaseProperties.class)
public class LeaseConfiguration {
}
