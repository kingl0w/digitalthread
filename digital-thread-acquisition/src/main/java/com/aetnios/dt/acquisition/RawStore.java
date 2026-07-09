package com.aetnios.dt.acquisition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** The raw zone. Verbatim payloads plus an append-only manifest. Never parses or mutates. */
public class RawStore {

    private final Path root;

    public RawStore(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
    }

    public static String sanitize(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]", "_");
    }

    private Path resolve(String category, String name, String ext) {
        return root.resolve(category).resolve(sanitize(name) + "." + ext);
    }

    public boolean exists(String category, String name, String ext) {
        return Files.exists(resolve(category, name, ext));
    }

    public String read(String category, String name, String ext) throws IOException {
        return Files.readString(resolve(category, name, ext), StandardCharsets.UTF_8);
    }

    public void write(String category, String name, String ext, String url, int status, byte[] body) throws IOException {
        Path target = resolve(category, name, ext);
        Files.createDirectories(target.getParent());
        Files.write(target, body);
        appendManifest(String.valueOf(status), body.length, root.relativize(target).toString(), url);
    }

    public void logFailure(String url, String reason) {
        try {
            appendManifest("FAIL:" + reason.replaceAll("[\\t\\n\\r]", " "), 0, "-", url);
        } catch (IOException e) {
            System.err.println("manifest append failed: " + e.getMessage());
        }
    }

    private synchronized void appendManifest(String status, long bytes, String path, String url) throws IOException {
        String line = String.join("\t", Instant.now().toString(), status,
                String.valueOf(bytes), path, url) + System.lineSeparator();
        Files.writeString(root.resolve("_manifest.tsv"), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
