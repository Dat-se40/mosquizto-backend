package com.mosquizto.api.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Only immutable, explicitly pinned filesystem artifacts are accepted. */
public final class ModelArtifacts {
    public static final String REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41";
    public static final String MODEL_SHA256 = "6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452";
    public static final String TOKENIZER_SHA256 = "be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037";

    private ModelArtifacts() {}

    public static Path verify(Path root, String revision, String modelHash, String tokenizerHash)
            throws IOException {
        if (revision == null || !revision.matches("[a-f0-9]{40}")) {
            throw new IllegalArgumentException("Model revision must be a full 40-character commit hash");
        }
        Path directory = root.toAbsolutePath().normalize().resolve(revision);
        verifyFile(directory.resolve("model.onnx"), modelHash);
        verifyFile(directory.resolve("tokenizer.json"), tokenizerHash);
        return directory;
    }

    public static void verifyFile(Path file, String expected) throws IOException {
        if (expected == null || !expected.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("Expected SHA-256 must have 64 hexadecimal characters");
        }
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IOException("Missing or unreadable model artifact: " + file.getFileName());
        }
        try (InputStream stream = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected)) {
                throw new IOException("SHA-256 mismatch for " + file.getFileName());
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
