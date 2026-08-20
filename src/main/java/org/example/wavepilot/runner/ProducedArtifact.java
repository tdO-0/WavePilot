package org.example.wavepilot.runner;

import org.example.wavepilot.artifact.ArtifactType;

import java.nio.file.Path;

public record ProducedArtifact(ArtifactType type, Path path) {
}
