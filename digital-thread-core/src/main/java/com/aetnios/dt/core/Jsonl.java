package com.aetnios.dt.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** One JSONL file, one line per node or edge. */
public class Jsonl implements AutoCloseable {

    static final ObjectMapper M = new ObjectMapper();

    private final BufferedWriter w;
    private long lines = 0;

    public Jsonl(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        w = Files.newBufferedWriter(file);
    }

    public static ObjectNode obj() {
        return M.createObjectNode();
    }

    public void write(ObjectNode node) throws IOException {
        w.write(node.toString());
        w.newLine();
        lines++;
    }

    public long lines() {
        return lines;
    }

    @Override
    public void close() throws IOException {
        w.close();
    }
}
