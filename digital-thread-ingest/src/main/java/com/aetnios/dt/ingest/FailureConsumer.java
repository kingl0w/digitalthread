package com.aetnios.dt.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The write service. Each event arrives with raw identity fields (N-number, serials) and the
 * consumer resolves them against the graph: same aircraft, one node, no matter which feed or how
 * many replays. Every write is a MERGE keyed on deterministic ids, so reprocessing is a no-op.
 */
@Component
public class FailureConsumer {

    private final Driver driver;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong seen = new AtomicLong(), assetHits = new AtomicLong(), assetMisses = new AtomicLong();

    public FailureConsumer(Driver driver, MeterRegistry metrics) {
        this.driver = driver;
        metrics.gauge("ingest.events.consumed", seen);
        metrics.gauge("ingest.resolution.asset.hits", assetHits);
        metrics.gauge("ingest.resolution.asset.misses", assetMisses);
    }

    @KafkaListener(id = "dt-ingest", topics = Replay.TOPIC, autoStartup = "${ingest.consume:true}")
    public void onEvent(String json) throws Exception {
        JsonNode e = mapper.readTree(json);
        String id = e.path("id").asText();
        if (id.isEmpty()) return;

        Map<String, Object> p = new HashMap<>();
        for (String k : new String[]{"date", "jascCode", "natureOfCondition", "stageOfOperation",
                "partCondition", "partLocation", "discrepancy", "source"}) {
            p.put(k, e.path(k).asText(""));
        }
        String nNumber = e.path("nNumber").asText("");
        String engineSerial = e.path("engineSerial").asText("");
        String partSerial = e.path("partSerial").asText("");
        String partNumber = e.path("partNumber").asText("");

        String validFrom = toIsoDate(e.path("date").asText(""));
        if (validFrom != null) p.put("validFrom", validFrom);

        try (Session s = driver.session()) {
            s.executeWrite(tx -> {
                tx.run("MERGE (f:Node:FailureEvent {id: $id}) ON CREATE SET f.recordedAt = datetime() SET f += $p",
                        Map.of("id", id, "p", p));

                // resolve the aircraft by N-number; find its airframe unit for composition
                String airframeUnit = null;
                if (!nNumber.isEmpty()) {
                    var res = tx.run("""
                            MATCH (a:Asset {id: $assetId})
                            OPTIONAL MATCH (a)<-[:INSTALLED_IN]-(af:SerializedUnit)
                            MERGE (f:FailureEvent {id: $id})
                            MERGE (f)-[r:ON_ASSET]->(a) ON CREATE SET r.recordedAt = datetime()
                            RETURN af.id AS af
                            """, Map.of("assetId", "N" + nNumber, "id", id)).list();
                    if (!res.isEmpty()) {
                        airframeUnit = res.get(0).get("af").asString(null);
                        assetHits.incrementAndGet();
                    } else {
                        assetMisses.incrementAndGet();
                    }
                }

                String engineUnit = null;
                if (!engineSerial.isEmpty()) {
                    engineUnit = "EN:" + engineSerial;
                    tx.run("MERGE (u:Node:SerializedUnit {id: $id}) ON CREATE SET u.recordedAt = datetime() "
                                    + "SET u.kind = 'engine', u.serial = $serial, u.source = 'faa/sdr'",
                            Map.of("id", engineUnit, "serial", engineSerial));
                    if (airframeUnit != null) {
                        tx.run("MATCH (af:SerializedUnit {id: $af}), (en:SerializedUnit {id: $en}) "
                                        + "MERGE (af)-[r:COMPOSED_OF]->(en) ON CREATE SET r.recordedAt = datetime()",
                                Map.of("af", airframeUnit, "en", engineUnit));
                    }
                }

                String partUnit = null;
                if (!partSerial.isEmpty()) {
                    partUnit = "PT:" + (partNumber.isEmpty() ? "UNKNOWN" : partNumber) + ":" + partSerial;
                    tx.run("MERGE (u:Node:SerializedUnit {id: $id}) ON CREATE SET u.recordedAt = datetime() "
                                    + "SET u.kind = 'part', u.serial = $serial, u.partNumber = $pn, u.source = 'faa/sdr'",
                            Map.of("id", partUnit, "serial", partSerial, "pn", partNumber));
                    String parent = engineUnit != null ? engineUnit : airframeUnit;
                    if (parent != null) {
                        tx.run("MATCH (p:SerializedUnit {id: $p}), (c:SerializedUnit {id: $c}) "
                                        + "MERGE (p)-[r:COMPOSED_OF]->(c) ON CREATE SET r.recordedAt = datetime()",
                                Map.of("p", parent, "c", partUnit));
                    }
                }

                String subject = partUnit != null ? partUnit : engineUnit != null ? engineUnit : airframeUnit;
                if (subject != null) {
                    tx.run("MATCH (f:FailureEvent {id: $id}), (u:SerializedUnit {id: $u}) "
                                    + "MERGE (f)-[r:ON_UNIT]->(u) ON CREATE SET r.recordedAt = datetime()",
                            Map.of("id", id, "u", subject));
                }
                return null;
            });
        }
        long n = seen.incrementAndGet();
        if (n % 1000 == 0) {
            System.out.printf("ingested %d events — asset resolution %d hit / %d miss%n",
                    n, assetHits.get(), assetMisses.get());
        }
    }

    /** SDR dates arrive as "1/9/2018 0:00:00", seed dates as ISO. Normalized to ISO for validFrom. */
    private static String toIsoDate(String raw) {
        if (raw.matches("^\\d{4}-\\d{2}-\\d{2}.*")) return raw.substring(0, 10);
        var m = java.util.regex.Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})").matcher(raw);
        if (m.find()) return String.format("%s-%02d-%02d",
                m.group(3), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        return null;
    }
}
