package org.example.wavepilot.replay;

import org.example.wavepilot.runner.MatlabTemplateCatalog;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.NoSuchElementException;

/**
 * SHA-256 over the fixed versioned MATLAB template resources (sorted file names with
 * contents). A template that is not in the versioned catalog (e.g. the in-process mock
 * runner) is represented by a digest of its version string, prefixed with "fallback:" so
 * readers can tell it is not a real MATLAB script digest.
 */
@Component
public class MatlabTemplateDigest {

    public String compute(String templateVersion) {
        if (templateVersion == null || templateVersion.isBlank()) {
            throw new IllegalArgumentException("A template version is required");
        }
        try {
            MatlabTemplateCatalog.MatlabTemplate template = MatlabTemplateCatalog.require(templateVersion);
            MessageDigest digest = sha256();
            for (String resource : template.resourceFiles().stream().sorted().toList()) {
                digest.update(resource.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (InputStream input = getClass().getResourceAsStream(
                        template.resourceRoot() + "/" + resource)) {
                    if (input == null) {
                        throw new IllegalStateException("Template resource is missing: " + resource);
                    }
                    digest.update(input.readAllBytes());
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read template resource " + resource, e);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchElementException e) {
            byte[] fallback = ("fallback:" + templateVersion).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(sha256().digest(fallback));
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
