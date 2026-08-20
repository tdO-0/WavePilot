package org.example.wavepilot.knowledge;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicOfflineEmbeddingServiceTest {

    private final DeterministicOfflineEmbeddingService service = new DeterministicOfflineEmbeddingService();

    @Test
    void equalTextAlwaysProducesEqualVectors() {
        assertArrayEquals(service.embed("极化码编码与生成矩阵"), service.embed("极化码编码与生成矩阵"));
        assertArrayEquals(service.embed("BEC 擦除信道可靠性排序"), service.embed("BEC 擦除信道可靠性排序"));
    }

    @Test
    void differentTextProducesDifferentVectors() {
        float[] polar = service.embed("极化码编码与生成矩阵");
        float[] bec = service.embed("BEC 擦除信道可靠性排序");
        assertFalse(Arrays.equals(polar, bec), "different terms must not collide to the same vector");
    }

    @Test
    void vectorsAreNormalizedAndDeterministicAcrossCalls() {
        float[] first = service.embed("通信仿真实验");
        float[] second = service.embed("通信仿真实验");
        double norm = Math.sqrt(java.util.stream.IntStream.range(0, first.length)
                .mapToDouble(index -> (double) first[index] * first[index]).sum());
        assertTrue(Math.abs(norm - 1.0) < 1.0e-6, "vectors must be unit length");
        assertArrayEquals(first, second, "the same input must be reproducible across calls");
    }

    @Test
    void blankTextIsRejectedAndProviderIsExplicit() {
        assertThrows(IllegalArgumentException.class, () -> service.embed("  "));
        assertTrue(service.providerDescription().contains("no DashScope call"),
                "the offline provider must be explicit about making no external calls");
    }
}
