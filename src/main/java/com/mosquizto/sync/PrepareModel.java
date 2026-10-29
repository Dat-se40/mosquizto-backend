package com.mosquizto.sync;

import com.mosquizto.api.util.ModelArtifacts;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;

/** One-off PVC population, with a single writer and atomic publication of verified artifacts. */
public final class PrepareModel {
    private PrepareModel() {}

    public static int run(Map<String, String> env) {
        Path staging = null;
        try {
            String revision = env.getOrDefault("EMBEDDING_MODEL_REVISION", ModelArtifacts.REVISION);
            if (!revision.matches("[a-f0-9]{40}")) {
                throw new IllegalArgumentException("Revision must be a full commit hash");
            }
            String modelHash = env.getOrDefault("EMBEDDING_MODEL_SHA256", ModelArtifacts.MODEL_SHA256);
            String tokenizerHash = env.getOrDefault("EMBEDDING_TOKENIZER_SHA256", ModelArtifacts.TOKENIZER_SHA256);
            Path root = Path.of(env.getOrDefault("EMBEDDING_MODEL_DIR", "/models")).toAbsolutePath().normalize();
            Files.createDirectories(root);
            try (var channel = FileChannel.open(root.resolve(".prepare.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) throw new IllegalStateException("Another model preparation is running");
                Path target = root.resolve(revision);
                if (Files.exists(target)) {
                    ModelArtifacts.verify(root, revision, modelHash, tokenizerHash);
                    System.out.println("Verified model already exists; no download: " + revision);
                    return 0;
                }
                staging = Files.createTempDirectory(root, ".download-");
                String base = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/" + revision + "/";
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.NORMAL).build();
                download(client, base + "onnx/model.onnx", staging.resolve("model.onnx"));
                download(client, base + "tokenizer.json", staging.resolve("tokenizer.json"));
                ModelArtifacts.verifyFile(staging.resolve("model.onnx"), modelHash);
                ModelArtifacts.verifyFile(staging.resolve("tokenizer.json"), tokenizerHash);
                Files.writeString(staging.resolve("SHA256SUMS"),
                        modelHash + "  model.onnx\n" + tokenizerHash + "  tokenizer.json\n");
                // Use the volume filesystem for staging, so readers never see half-published files.
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                staging = null;
                System.out.println("Published verified model: " + revision);
                return 0;
            }
        } catch (Exception e) {
            System.err.println("Model preparation failed: " + e.getClass().getSimpleName());
            return 1;
        } finally {
            if (staging != null) {
                // Only delete the directory created by this invocation.
                try (var paths = Files.walk(staging)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (IOException ignored) {}
            }
        }
    }

    private static void download(HttpClient client, String url, Path destination) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() != 200) {
            throw new IOException("Artifact download returned HTTP " + response.statusCode());
        }
    }
}
