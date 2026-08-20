package org.example.wavepilot.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "wavepilot.runner.local-matlab")
public class LocalMatlabRunnerProperties {

    private String executable = "matlab";
    private String template = MatlabTemplateCatalog.SIMPLE_TEMPLATE;
    private Duration timeout = Duration.ofMinutes(10);
    private Duration pollInterval = Duration.ofMillis(200);
    private Duration shutdownGrace = Duration.ofSeconds(5);

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = executable;
    }

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getShutdownGrace() {
        return shutdownGrace;
    }

    public void setShutdownGrace(Duration shutdownGrace) {
        this.shutdownGrace = shutdownGrace;
    }
}
