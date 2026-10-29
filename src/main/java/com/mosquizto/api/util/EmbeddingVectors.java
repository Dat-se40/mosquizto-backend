package com.mosquizto.api.util;

/** Validation shared by inference and the vector-store write boundary. */
public final class EmbeddingVectors {
    public static final int DIMENSIONS = 384;

    private EmbeddingVectors() {}

    public static void requireValid(float[] vector) {
        if (vector == null || vector.length != DIMENSIONS) {
            throw new IllegalArgumentException("Embedding must have " + DIMENSIONS + " dimensions");
        }
        double normSquared = 0;
        for (float component : vector) {
            if (!Float.isFinite(component)) {
                throw new IllegalArgumentException("Embedding contains a non-finite component");
            }
            normSquared += (double) component * component;
        }
        if (normSquared <= 1e-12) {
            throw new IllegalArgumentException("Embedding has zero norm");
        }
    }
}
