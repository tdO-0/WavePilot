package org.example.wavepilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "file.upload")
public class KnowledgeUploadProperties {

    private String path = "./uploads";
    private String allowedExtensions = "txt,md";
    private long maxSizeBytes = 5 * 1024 * 1024;

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getAllowedExtensions() { return allowedExtensions; }
    public void setAllowedExtensions(String allowedExtensions) { this.allowedExtensions = allowedExtensions; }
    public long getMaxSizeBytes() { return maxSizeBytes; }
    public void setMaxSizeBytes(long maxSizeBytes) { this.maxSizeBytes = maxSizeBytes; }
}
