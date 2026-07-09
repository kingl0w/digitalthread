package com.aetnios.dt.acquisition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public record Scope(List<String> makes, int yearFrom, int yearTo) {

    static Scope load() {
        try (InputStream in = Scope.class.getResourceAsStream("/scope.json")) {
            if (in == null) throw new IllegalStateException("scope.json not on classpath");
            JsonNode root = new ObjectMapper().readTree(in);
            List<String> makes = new ArrayList<>();
            root.get("makes").forEach(n -> makes.add(n.asText()));
            return new Scope(makes, root.get("yearFrom").asInt(), root.get("yearTo").asInt());
        } catch (Exception e) {
            throw new RuntimeException("failed to load scope.json", e);
        }
    }
}
