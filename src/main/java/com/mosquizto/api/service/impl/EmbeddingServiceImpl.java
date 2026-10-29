package com.mosquizto.api.service.impl;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.mosquizto.api.model.Collection;
import com.mosquizto.api.service.EmbeddingService;
import com.mosquizto.api.util.EmbeddingVectors;
import com.mosquizto.api.util.ModelArtifacts;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Map;

@Service
@Slf4j
public class EmbeddingServiceImpl implements EmbeddingService {
    @Value("${embedding.model-dir:/models}")
    private String modelDir = "/models";
    @Value("${embedding.model-revision:" + ModelArtifacts.REVISION + "}")
    private String revision = ModelArtifacts.REVISION;
    @Value("${embedding.model-sha256:" + ModelArtifacts.MODEL_SHA256 + "}")
    private String modelHash = ModelArtifacts.MODEL_SHA256;
    @Value("${embedding.tokenizer-sha256:" + ModelArtifacts.TOKENIZER_SHA256 + "}")
    private String tokenizerHash = ModelArtifacts.TOKENIZER_SHA256;

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private volatile boolean initialized;

    /** API can run degraded. CLI calls init() directly and propagates failure. */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeForApi() {
        try {
            init();
        } catch (RuntimeException e) {
            log.warn("Embedding unavailable; vector writes will fail: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void init() {
        if (initialized) {
            return;
        }
        try {
            Path directory = ModelArtifacts.verify(Path.of(modelDir), revision, modelHash, tokenizerHash);
            env = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(1);
                session = env.createSession(directory.resolve("model.onnx").toString(), options);
            }
            tokenizer = HuggingFaceTokenizer.builder().optTokenizerPath(directory.resolve("tokenizer.json"))
                    .optTruncation(true).optMaxLength(256).build();
            initialized = true;
            embed("Model initialization validation");
            log.info("Embedding ready: revision={}, dimensions={}", revision, EmbeddingVectors.DIMENSIONS);
        } catch (Exception | LinkageError e) {
            close();
            throw new IllegalStateException("Cannot initialize verified embedding model", e);
        }
    }

    @Override
    public synchronized float[] embed(String text) {
        if (!initialized) {
            throw new IllegalStateException("Embedding model is not initialized");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding text must not be empty");
        }
        try {
            Encoding encoding = tokenizer.encode(text);
            // The sentence-transformers model uses a 256-token maximum.
            long[] ids = encoding.getIds();
            long[] mask = encoding.getAttentionMask();
            long[] types = encoding.getTypeIds();
            long[] shape = {1, ids.length};
            try (OnnxTensor input = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape);
                 OnnxTensor attention = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape);
                 OnnxTensor tokenTypes = OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape);
                 OrtSession.Result result = session.run(Map.of(
                         "input_ids", input, "attention_mask", attention, "token_type_ids", tokenTypes))) {
                float[][][] embeddings = (float[][][]) result.get(0).getValue();
                return meanPooling(embeddings[0], mask);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Embedding inference failed", e);
        }
    }

    @Override
    public float[] embedCollection(Collection collection) {
        if (collection == null) {
            throw new IllegalArgumentException("Collection must not be null");
        }
        String title = collection.getTitle() == null ? "" : collection.getTitle().trim();
        String description = collection.getDescription() == null ? "" : collection.getDescription().trim();
        return embed((title + (title.isEmpty() || description.isEmpty() ? "" : ". ") + description).trim());
    }

    static float[] meanPooling(float[][] embeddings, long[] mask) {
        if (embeddings.length == 0 || embeddings.length != mask.length) {
            throw new IllegalArgumentException("Invalid token embedding shape");
        }
        float[] result = new float[EmbeddingVectors.DIMENSIONS];
        long maskSum = 0;
        for (int i = 0; i < embeddings.length; i++) {
            if (embeddings[i].length != result.length) {
                throw new IllegalArgumentException("Invalid embedding dimensions");
            }
            maskSum += mask[i];
            for (int j = 0; j < result.length; j++) {
                result[j] += embeddings[i][j] * mask[i];
            }
        }
        if (maskSum <= 0) {
            throw new IllegalArgumentException("Empty attention mask");
        }
        for (int j = 0; j < result.length; j++) {
            result[j] /= maskSum;
        }
        EmbeddingVectors.requireValid(result);
        double norm = 0;
        for (float value : result) {
            norm += (double) value * value;
        }
        norm = Math.sqrt(norm);
        for (int j = 0; j < result.length; j++) {
            result[j] /= norm;
        }
        return result;
    }

    @PreDestroy
    public synchronized void close() {
        initialized = false;
        if (tokenizer != null) {
            tokenizer.close();
            tokenizer = null;
        }
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("Could not close ONNX session");
            }
            session = null;
        }
        // OrtEnvironment is shared by this JVM.
    }
}
