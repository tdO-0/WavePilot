package org.example.wavepilot.frontend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads the static workbench files from the classpath for contract assertions. */
public final class FrontendTestSupport {

    private FrontendTestSupport() { }

    public static String appJs() {
        return read("/static/app.js");
    }

    public static String indexHtml() {
        return read("/static/index.html");
    }

    public static String stylesCss() {
        return read("/static/styles.css");
    }

    public static String read(String resource) {
        try (InputStream input = FrontendTestSupport.class.getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("Missing static resource: " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("Cannot read static resource: " + resource, e);
        }
    }
}
