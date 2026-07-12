package com.aetnios.dt.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Semantic layer over the loaded graph: embeds FailureEvent text with a local Ollama model and
 * indexes it for vector search, so free-text descriptions become entry points into the exact
 * lineage traversals. Run after GraphApp; idempotent (only embeds events still missing a vector).
 *
 *   mvn -q compile exec:java -Dapp.main=com.aetnios.dt.core.Embed
 */
public class Embed {

    static final String INDEX = "failure_text";
    static final String MODEL = "nomic-embed-text";
    static final int DIMENSIONS = 768;
    private static final int BATCH = 64;

    public static void main(String[] args) throws Exception {
        String uri = System.getProperty("neo4j.uri", "bolt://localhost:7687");
        String user = System.getProperty("neo4j.user", "neo4j");
        String pass = System.getProperty("neo4j.pass", "digitalthread");
        String ollama = System.getProperty("ollama.url", "http://localhost:11434");

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, pass));
             Session session = driver.session()) {
            session.run(("CREATE VECTOR INDEX %s IF NOT EXISTS FOR (f:FailureEvent) ON (f.embedding) "
                    + "OPTIONS {indexConfig: {`vector.dimensions`: %d, `vector.similarity_function`: 'cosine'}}")
                    .formatted(INDEX, DIMENSIONS)).consume();

            // one text per event, mirroring what a human would read: location, condition, narrative
            List<Map<String, String>> pending = session.run("""
                    MATCH (f:FailureEvent) WHERE f.embedding IS NULL
                    RETURN f.id AS id, trim(coalesce(f.partLocation, '') + ' ' + coalesce(f.partCondition, '')
                           + ' ' + coalesce(f.discrepancy, f.mode, '')) AS text
                    """).list(r -> Map.of("id", r.get("id").asString(), "text", r.get("text").asString()));
            System.out.printf("embedding %d failure events via %s (%s)%n", pending.size(), ollama, MODEL);

            HttpClient http = HttpClient.newHttpClient();
            long done = 0;
            for (int i = 0; i < pending.size(); i += BATCH) {
                List<Map<String, String>> batch = pending.subList(i, Math.min(i + BATCH, pending.size()));
                List<List<Double>> vectors = embed(http, ollama, batch.stream()
                        .map(m -> "search_document: " + m.get("text")).toList());
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int j = 0; j < batch.size(); j++) {
                    rows.add(Map.of("id", batch.get(j).get("id"), "vec", vectors.get(j)));
                }
                session.executeWrite(tx -> tx.run("""
                        UNWIND $rows AS row MATCH (f:FailureEvent {id: row.id})
                        CALL db.create.setNodeVectorProperty(f, 'embedding', row.vec)
                        """, Map.of("rows", rows)).consume());
                done += batch.size();
                if (done % 640 == 0 || done == pending.size()) System.out.printf("  %d/%d%n", done, pending.size());
            }

            long indexed = session.run("MATCH (f:FailureEvent) WHERE f.embedding IS NOT NULL RETURN count(f) AS c")
                    .single().get("c").asLong();
            System.out.printf("done: %d failure events carry embeddings, index '%s' ready%n", indexed, INDEX);
        }
    }

    static List<List<Double>> embed(HttpClient http, String ollama, List<String> inputs) throws Exception {
        ObjectNode body = Jsonl.obj().put("model", MODEL);
        ArrayNode arr = body.putArray("input");
        inputs.forEach(arr::add);
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(ollama + "/api/embed"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Jsonl.M.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IllegalStateException("ollama " + resp.statusCode() + ": " + resp.body());
        List<List<Double>> out = new ArrayList<>();
        for (JsonNode vec : Jsonl.M.readTree(resp.body()).path("embeddings")) {
            List<Double> v = new ArrayList<>(DIMENSIONS);
            vec.forEach(d -> v.add(d.asDouble()));
            out.add(v);
        }
        return out;
    }
}
