package org.example.wavepilot.autonomous;

import java.util.List;

/**
 * The model boundary of the autonomous loop. A real implementation delegates to the
 * configured ChatModel; the stub implementation is scripted so offline tests are
 * deterministic and never call DashScope.
 */
public interface AutonomousModel {

    String name();

    /** Returns the model's next output (tool-call JSON or finish) for the given conversation. */
    String respond(List<String> chatHistory);
}
