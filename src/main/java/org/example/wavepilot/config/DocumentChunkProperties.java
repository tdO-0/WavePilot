package org.example.wavepilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "document.chunk")
public class DocumentChunkProperties {

    private int maxSize = 800;
    private int overlap = 100;

    public int getMaxSize() { return maxSize; }
    public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
    public int getOverlap() { return overlap; }
    public void setOverlap(int overlap) { this.overlap = overlap; }
}
