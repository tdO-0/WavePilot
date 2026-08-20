package org.example.wavepilot.replay;

/** Client-supplied metadata for a replay; the replay itself is derived from the source job. */
public record ReplayRequest(String note) {
    public ReplayRequest {
        note = note == null ? "" : note;
    }
}
