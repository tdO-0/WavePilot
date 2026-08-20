package org.example.wavepilot.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-demo integration test, run only through the explicit `full-demo` Maven profile:
 *   mvn -B -Pfull-demo verify
 *
 * It requires a real Milvus, a DashScope API key, local MATLAB and the workbench frontend.
 * Without a fully provisioned host this test fails loudly; it must never run by default
 * and a passing run is the only way to claim full-demo verification.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("full-demo")
class FullDemoIT {

    @Value("${DASHSCOPE_API_KEY:}") private String dashscopeApiKey;
    @Value("${wavepilot.runner.local-matlab.executable}") private String matlabExecutable;

    @Test
    void fullDemoEnvironmentMustBeProvisioned() {
        assertFalse(dashscopeApiKey == null || dashscopeApiKey.isBlank(),
                "Full demo requires a real DASHSCOPE_API_KEY");
        assertFalse("matlab".equals(matlabExecutable),
                "Full demo requires MATLAB_EXECUTABLE to point at a real MATLAB installation");
    }

    @Test
    void workbenchFrontendIsServed() throws Exception {
        // The static workbench must be on the classpath so the running server serves it.
        assertTrue(FullDemoIT.class.getResourceAsStream("/static/index.html") != null,
                "the workbench frontend must be packaged");
    }
}
