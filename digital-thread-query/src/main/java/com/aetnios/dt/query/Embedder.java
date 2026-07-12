package com.aetnios.dt.query;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns free text into a query vector via a local Ollama model. The LLM-facing entry point stays
 * fuzzy; everything downstream of the vector lookup is exact graph traversal. Disabled (clean
 * error, no crash) when dt.embed.url is empty — the hosted demo has no embedding service.
 */
@Component
public class Embedder {

    private final String url;
    private final String model;
    private final RestClient http = RestClient.create();

    public Embedder(@Value("${dt.embed.url:}") String url,
                    @Value("${dt.embed.model:nomic-embed-text}") String model) {
        this.url = url;
        this.model = model;
    }

    /** nomic-embed-text is trained with task prefixes: documents get search_document, queries this. */
    public List<Double> embed(String text) {
        if (url.isBlank()) throw new IllegalStateException(
                "semantic search is not enabled on this deployment (dt.embed.url is unset)");
        JsonNode resp;
        try {
            resp = http.post().uri(url + "/api/embed")
                    .body(Map.of("model", model, "input", List.of("search_query: " + text)))
                    .retrieve().body(JsonNode.class);
        } catch (Exception e) {
            throw new IllegalStateException("embedding service unreachable at " + url, e);
        }
        List<Double> vec = new ArrayList<>();
        resp.path("embeddings").path(0).forEach(d -> vec.add(d.asDouble()));
        if (vec.isEmpty()) throw new IllegalStateException("embedding service returned no vector");
        return vec;
    }
}
