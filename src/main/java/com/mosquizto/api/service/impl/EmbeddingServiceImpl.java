package com.mosquizto.api.service.impl;

import ai.djl.Application;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.mosquizto.api.model.Collection;
import com.mosquizto.api.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@Service
@Slf4j
public class EmbeddingServiceImpl implements EmbeddingService {
    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private boolean isInitialized = false ;

    @Override
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (getClass().getResource("/models/model.onnx") == null
                || getClass().getResource("/models/tokenizer.json") == null) {
            log.warn("[EmbeddingService] ONNX model is not on the classpath. Recommendation embedding stays off and the server keeps running.");
            return;
        }

        try {
            log.info("[EmbeddingService] Loading ONNX model...");
            long startTime = System.currentTimeMillis();

            env = OrtEnvironment.getEnvironment();

            log.info("[EmbeddingService] Reading model.onnx from classpath...");
            try (InputStream is = getClass().getResourceAsStream("/models/model.onnx")) {
                byte[] modelBytes = is.readAllBytes();
                log.info("[EmbeddingService] model.onnx size: {} bytes", modelBytes.length);
                session = env.createSession(modelBytes, new OrtSession.SessionOptions());
            }

            log.info("[EmbeddingService] Loading tokenizer.json...");
            Path tokenizerPath = Paths.get(
                    getClass().getResource("/models/tokenizer.json").toURI()
            );
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);

            isInitialized = true;
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[EmbeddingService] ONNX model loaded in {}ms", elapsed);
        } catch (Exception e) {
            log.error("[EmbeddingService] Failed to initialize ONNX model. Recommendation embedding stays off.", e);
        }
    }

    @Override
    public float[] embed(String text) {
        if (!isInitialized) {
            log.warn("[EmbeddingService] Not initialized, returning zero vector");
            return new float[384];
        }

        try {
            Encoding encoding = tokenizer.encode(text);

            long[] inputIds      = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            long[] tokenTypeIds  = encoding.getTypeIds();

            // Shape: [1, seq_len]
            long[] shape = {1, inputIds.length};

            Map<String, OnnxTensor> inputs = Map.of(
                    "input_ids",      OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape),
                    "attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape),
                    "token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape)
            );

            try (OrtSession.Result result = session.run(inputs)) {
                // Output shape: [1, seq_len, 384]
                float[][][] tokenEmbeddings = (float[][][]) result.get(0).getValue();
                return meanPooling(tokenEmbeddings[0], attentionMask);
            }

        } catch (Exception e) {
            log.error("[EmbeddingService] Embed error: {}", e.getMessage(), e);
            return new float[384];
        }
    }

    @Override
    public float[] embedCollection(Collection collection) {
        // 1. Kiểm tra an toàn
        if (collection == null) {
            log.warn("[Embedding] Collection is null, returning empty vector");
            return new float[384];
        }

        // 2. Ghép Title và Description
        StringBuilder textToEmbed = new StringBuilder();

        if (collection.getTitle() != null && !collection.getTitle().isBlank()) {
            textToEmbed.append(collection.getTitle().trim());
        }

        if (collection.getDescription() != null && !collection.getDescription().isBlank()) {
            textToEmbed.append(". ").append(collection.getDescription().trim());
        }

        String finalText = textToEmbed.toString();

        // Tránh trường hợp cả title và description đều trống
        if (finalText.isBlank()) {
            log.warn("[Embedding] Collection ID {} has empty text, returning zero vector", collection.getId());
            return new float[384];
        }

        // 3. Gọi model ONNX để tạo mảng 384 chiều thực sự
        return embed(finalText);
    }

    // Mean pooling — lấy trung bình có trọng số theo attention mask
    // Vector/chiều dài vector
    private float[] meanPooling(float[][] tokenEmbeddings, long[] attentionMask) {
        int seqLen = tokenEmbeddings.length;
        int dims   = tokenEmbeddings[0].length;  // 384
        float[] result = new float[dims];
        float maskSum  = 0;

        for (int i = 0; i < seqLen; i++) {
            float m = attentionMask[i];
            maskSum += m;
            for (int j = 0; j < dims; j++) {
                result[j] += tokenEmbeddings[i][j] * m;
            }
        }

        // Normalize
        float norm = 0;
        for (int j = 0; j < dims; j++) {
            result[j] /= Math.max(maskSum, 1e-9f);
            norm += result[j] * result[j];
        }
        norm = (float) Math.sqrt(norm);
        for (int j = 0; j < dims; j++) {
            result[j] /= norm;
        }

        return result;
    }
}
