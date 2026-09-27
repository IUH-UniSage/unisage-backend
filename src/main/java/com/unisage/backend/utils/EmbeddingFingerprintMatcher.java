package com.unisage.backend.utils;

/**
 * Compares two embedding fingerprints (plan.md "Embedding identity guard") — used both when SA
 * activates/swaps an EMBEDDING row (against {@code embedding_index_identity}) and when promoting
 * a verified candidate onto a row that's currently ACTIVE (same comparison, different caller).
 * A single flat {@code real[]} column is what Task 1 shipped (rather than the 3 separate probe
 * vectors plan.md's JSON example shows), so this compares the whole flattened vector with cosine
 * similarity — a reasonable single distance metric given that storage shape.
 */
public final class EmbeddingFingerprintMatcher {

    /** plan.md: "cosine mỗi probe ≥ 0.999" is treated as "the same embedding space". */
    public static final double SIMILARITY_THRESHOLD = 0.999;

    private EmbeddingFingerprintMatcher() {
    }

    public static boolean matches(Float[] a, Float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return false;
        }
        return cosineSimilarity(a, b) >= SIMILARITY_THRESHOLD;
    }

    public static double cosineSimilarity(Float[] a, Float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
