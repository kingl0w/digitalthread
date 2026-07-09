package com.aetnios.dt.core;

import com.fasterxml.jackson.databind.JsonNode;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;

/**
 * Loads the canonical and seed zones into Neo4j, then proves the two money queries in raw Cypher
 * against the ground-truth manifest. Build order step 1 ends here. Every node also carries the
 * :Node label so edges resolve endpoints through one global unique id index.
 */
public class GraphApp {

    private static final int BATCH = 5000;

    public static void main(String[] args) throws Exception {
        String uri = System.getProperty("neo4j.uri", "bolt://localhost:7687");
        String user = System.getProperty("neo4j.user", "neo4j");
        String pass = System.getProperty("neo4j.pass", "digitalthread");
        Path canonical = Path.of(System.getProperty("canonical", "data/canonical"));
        Path seed = Path.of(System.getProperty("seed", "data/seed"));

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, pass));
             Session session = driver.session()) {

            session.run("MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS").consume();
            session.run("CREATE CONSTRAINT node_id IF NOT EXISTS FOR (n:Node) REQUIRE n.id IS UNIQUE").consume();

            for (Path dir : List.of(canonical, seed)) {
                for (String file : List.of("asset", "unit", "revision", "failure_event", "campaign",
                        "lot", "supplier", "work_order")) {
                    Path p = dir.resolve(file + ".jsonl");
                    if (Files.exists(p)) loadNodes(session, p);
                }
            }
            loadEdges(session, List.of(canonical.resolve("edges.jsonl"), seed.resolve("edges.jsonl")));
            linkFailures(session, List.of(canonical.resolve("failure_event.jsonl"), seed.resolve("failure_event.jsonl")));

            JsonNode gt = Jsonl.M.readTree(seed.resolve("ground_truth.json").toFile());
            realRecalls(session);
            boolean ok = blastRadiusLot(session, gt)
                    & blastRadiusRevision(session, gt)
                    & rootCause(session, gt);
            System.out.println(ok ? "MONEY QUERIES: BOTH PASS" : "MONEY QUERIES: FAILURE");
            if (!ok) System.exit(1);
        }
    }

    private static void loadNodes(Session session, Path file) throws Exception {
        String label = null;
        List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0;
        for (String line : Files.readAllLines(file)) {
            JsonNode n = Jsonl.M.readTree(line);
            label = n.path("label").asText();
            Map<String, Object> props = new HashMap<>();
            Iterator<String> it = n.fieldNames();
            while (it.hasNext()) {
                String k = it.next();
                if (!k.equals("label") && !n.get(k).isNull()) props.put(k, n.get(k).asText());
            }
            rows.add(Map.of("id", n.path("id").asText(), "props", props));
            if (rows.size() >= BATCH) { total += flushNodes(session, label, rows); }
        }
        if (!rows.isEmpty()) total += flushNodes(session, label, rows);
        System.out.printf("loaded %d %s from %s%n", total, label, file);
    }

    private static int flushNodes(Session session, String label, List<Map<String, Object>> rows) {
        int n = rows.size();
        session.executeWrite(tx -> tx.run(
                "UNWIND $rows AS row MERGE (n:Node:" + label + " {id: row.id}) SET n += row.props",
                Map.of("rows", new ArrayList<>(rows))).consume());
        rows.clear();
        return n;
    }

    private static void loadEdges(Session session, List<Path> files) throws Exception {
        Map<String, List<Map<String, Object>>> byType = new HashMap<>();
        long total = 0;
        for (Path file : files) {
            for (String line : Files.readAllLines(file)) {
                JsonNode e = Jsonl.M.readTree(line);
                byType.computeIfAbsent(e.path("type").asText(), k -> new ArrayList<>())
                        .add(Map.of("from", e.path("from").asText(), "to", e.path("to").asText()));
            }
        }
        for (Map.Entry<String, List<Map<String, Object>>> en : byType.entrySet()) {
            total += flushEdges(session, en.getKey(), en.getValue());
        }
        System.out.printf("loaded %d edges across %d types%n", total, byType.size());
    }

    private static long flushEdges(Session session, String type, List<Map<String, Object>> rows) {
        for (int i = 0; i < rows.size(); i += BATCH) {
            List<Map<String, Object>> batch = rows.subList(i, Math.min(i + BATCH, rows.size()));
            session.executeWrite(tx -> tx.run(
                    "UNWIND $rows AS row MATCH (a:Node {id: row.from}) MATCH (b:Node {id: row.to}) "
                            + "MERGE (a)-[:" + type + "]->(b)",
                    Map.of("rows", new ArrayList<>(batch))).consume());
        }
        return rows.size();
    }

    /** Failure events carry unitId/assetId as properties; materialize them as ON_UNIT / ON_ASSET edges. */
    private static void linkFailures(Session session, List<Path> files) throws Exception {
        List<Map<String, Object>> onUnit = new ArrayList<>(), onAsset = new ArrayList<>();
        for (Path file : files) {
            for (String line : Files.readAllLines(file)) {
                JsonNode e = Jsonl.M.readTree(line);
                String id = e.path("id").asText();
                if (e.hasNonNull("unitId")) onUnit.add(Map.of("from", id, "to", e.path("unitId").asText()));
                if (e.hasNonNull("assetId")) onAsset.add(Map.of("from", id, "to", e.path("assetId").asText()));
            }
        }
        long a = flushEdges(session, "ON_UNIT", onUnit);
        long b = flushEdges(session, "ON_ASSET", onAsset);
        System.out.printf("linked failures: %d ON_UNIT, %d ON_ASSET%n", a, b);
    }

    /** Real recalls: AD campaigns linked to designs by full-text parse. No oracle; report reach. */
    private static void realRecalls(Session session) {
        session.run("""
                MATCH (c:Campaign)-[:AFFECTS]->(r:Revision)<-[:BUILT_TO]-(:SerializedUnit)-[:INSTALLED_IN]->(a:Asset)
                WITH c, count(DISTINCT r) AS designs, count(DISTINCT a) AS fleet
                RETURN c.id AS id, left(c.title, 70) AS title, designs, fleet
                ORDER BY fleet DESC LIMIT 3
                """).list().forEach(r -> System.out.printf("real recall %s: %d designs, %d registered aircraft — %s%n",
                r.get("id").asString(), r.get("designs").asInt(), r.get("fleet").asInt(), r.get("title").asString()));
    }

    /** Money query 1a: bad lot -> every deployed asset transitively containing an affected unit. */
    private static boolean blastRadiusLot(Session session, JsonNode gt) {
        String lotId = gt.path("badLot").path("lotId").asText();
        Set<String> got = new TreeSet<>(session.run("""
                MATCH (:MaterialLot {id: $lotId})<-[:MADE_FROM]-(u:SerializedUnit)
                MATCH (u)<-[:COMPOSED_OF*0..]-(root:SerializedUnit)-[:INSTALLED_IN]->(a:Asset)
                RETURN DISTINCT a.id AS asset
                """, Map.of("lotId", lotId)).list(r -> r.get("asset").asString()));
        return check("blast radius (bad lot " + lotId + ")", got, expected(gt.path("badLot")));
    }

    /** Money query 1b: bad revision -> same traversal, entered through BUILT_TO. */
    private static boolean blastRadiusRevision(Session session, JsonNode gt) {
        String revId = gt.path("badRevision").path("revisionId").asText();
        Set<String> got = new TreeSet<>(session.run("""
                MATCH (:Revision {id: $revId})<-[:BUILT_TO]-(u:SerializedUnit)
                MATCH (u)<-[:COMPOSED_OF*0..]-(root:SerializedUnit)-[:INSTALLED_IN]->(a:Asset)
                RETURN DISTINCT a.id AS asset
                """, Map.of("revId", revId)).list(r -> r.get("asset").asString()));
        return check("blast radius (bad revision " + revId + ")", got, expected(gt.path("badRevision")));
    }

    /** Money query 2: failure cluster -> walk back through the physical tree to the common lot. */
    private static boolean rootCause(Session session, JsonNode gt) {
        List<String> events = new ArrayList<>();
        gt.path("failureCluster").path("events").forEach(n -> events.add(n.asText()));
        String expectedLot = gt.path("failureCluster").path("expectedRootCauseLot").asText();
        List<String> ranked = session.run("""
                UNWIND $events AS eid
                MATCH (:FailureEvent {id: eid})-[:ON_UNIT]->(u:SerializedUnit)
                MATCH (u)-[:COMPOSED_OF*0..]->(leaf:SerializedUnit)-[:MADE_FROM]->(lot:MaterialLot)
                WITH lot, count(DISTINCT eid) AS hits
                RETURN lot.id AS lot, hits ORDER BY hits DESC, lot LIMIT 3
                """, Map.of("events", events)).list(r -> r.get("lot").asString() + " (" + r.get("hits").asInt() + ")");
        boolean ok = !ranked.isEmpty() && ranked.get(0).startsWith(expectedLot + " (" + events.size() + ")");
        System.out.printf("%s root cause: cluster of %d -> %s, expected %s%n",
                ok ? "PASS" : "FAIL", events.size(), ranked, expectedLot);
        return ok;
    }

    private static Set<String> expected(JsonNode section) {
        Set<String> s = new TreeSet<>();
        section.path("expectedAffectedAssets").forEach(n -> s.add(n.asText()));
        return s;
    }

    private static boolean check(String name, Set<String> got, Set<String> want) {
        boolean ok = got.equals(want);
        System.out.printf("%s %s: %d assets found, %d expected%n", ok ? "PASS" : "FAIL", name, got.size(), want.size());
        return ok;
    }
}
