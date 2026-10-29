package com.mosquizto.api.service.impl;

import com.mosquizto.api.model.Collection;
import com.mosquizto.api.util.EmbeddingVectors;
import com.mosquizto.api.util.ModelArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingServiceImplTest {
    @TempDir Path directory;

    @Test void unavailableModelAndEmptyCollectionNeverProduceZeroVectors() {
        var service = new EmbeddingServiceImpl();
        assertThrows(IllegalStateException.class, () -> service.embed("Java"));
        assertThrows(IllegalArgumentException.class, () -> service.embedCollection(null));
        assertThrows(RuntimeException.class, () -> service.embedCollection(new Collection()));
    }

    @Test void checksumRejectsMissingCorruptAndTraversingRevision() throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("valid".getBytes()));
        Path file = directory.resolve("model.onnx");
        assertThrows(java.io.IOException.class, () -> ModelArtifacts.verifyFile(file, digest));
        Files.writeString(file, "valid");
        ModelArtifacts.verifyFile(file, digest);
        Files.writeString(file, "corrupt");
        assertThrows(java.io.IOException.class, () -> ModelArtifacts.verifyFile(file, digest));
        assertThrows(IllegalArgumentException.class,
                () -> ModelArtifacts.verify(directory, "../elsewhere", digest, digest));
    }

    @Test void poolingMasksPaddingAndNormalizes() {
        float[][] tokens = new float[2][384];
        tokens[0][0] = 3;
        tokens[0][1] = 4;
        tokens[1][2] = 999;
        float[] vector = EmbeddingServiceImpl.meanPooling(tokens, new long[]{1, 0});
        assertEquals(0.6f, vector[0], 1e-6);
        assertEquals(0.8f, vector[1], 1e-6);
        assertEquals(0, vector[2]);
    }

    @Test void rejectsZeroNanInfinityWrongDimensionAndEmptyMask() {
        assertThrows(IllegalArgumentException.class, () -> EmbeddingVectors.requireValid(new float[384]));
        assertThrows(IllegalArgumentException.class, () -> EmbeddingVectors.requireValid(new float[3]));
        float[] invalid = new float[384];
        invalid[0] = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> EmbeddingVectors.requireValid(invalid));
        invalid[0] = Float.POSITIVE_INFINITY;
        assertThrows(IllegalArgumentException.class, () -> EmbeddingVectors.requireValid(invalid));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingServiceImpl.meanPooling(new float[1][384], new long[]{0}));
    }

    // Optional real native inference; requires a populated, verified model directory.
    @Test
    @EnabledIfSystemProperty(named = "embedding.test.model-dir", matches = ".+")
    void realPinnedModelProducesStableNonzeroVectors() {
        var service = new EmbeddingServiceImpl();
        ReflectionTestUtils.setField(service, "modelDir", System.getProperty("embedding.test.model-dir"));
        try {
            service.init();
            float[] first = service.embed("Java programming and Spring Boot");
            float[] repeated = service.embed("Java programming and Spring Boot");
            float[] different = service.embed("A tropical forest full of birds");
            EmbeddingVectors.requireValid(first);
            assertArrayEquals(first, repeated, 1e-5f);
            assertFalse(java.util.Arrays.equals(first, different));
            assertThrows(IllegalArgumentException.class, () -> service.embed(" "));
            for (int i = 0; i < 100; i++) service.embed("Repeated inference " + i);
            EmbeddingVectors.requireValid(service.embed("long text ".repeat(1000)));
        } finally { service.close(); }
    }
}
