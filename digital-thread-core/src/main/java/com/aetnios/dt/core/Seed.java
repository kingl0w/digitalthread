package com.aetnios.dt.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.aetnios.dt.core.Transform.Airframe;

/**
 * The synthetic middle no public source exposes: BOM revisions, suppliers, material lots, work
 * orders, and assembly trees, anchored on real registry airframes. Emits the ground-truth defect
 * manifest the money-query tests assert against, then self-checks blast radius from the emitted
 * files, not from memory.
 */
public class Seed {

    private static final String[] SUBSYSTEMS = {"ENGINE", "FUSELAGE", "WING", "EMPENNAGE", "LANDING_GEAR", "AVIONICS"};
    private static final String[] LEAVES = {"PRIMARY", "SECONDARY"};
    private static final int LOT_BATCH = 20;

    private final Path canonical;
    private final Path out;
    private final long rngSeed;
    private final int fleetSize;
    private final List<Airframe> airframes;

    private final Random rng;
    private final Map<String, String[]> designVariants = new TreeMap<>();  // revId base -> [revA, revB|null]
    private final Map<String, String> parentOf = new HashMap<>();          // COMPOSED_OF child -> parent
    private final Map<String, String> rootAsset = new HashMap<>();         // airframe unit -> asset
    private final Map<String, List<String>> unitsByLot = new TreeMap<>();
    private final Map<String, List<String>> unitsByRev = new TreeMap<>();
    private final Map<String, Integer> lotCursor = new HashMap<>();
    private final Map<String, String> lotOf = new HashMap<>();             // active lot per leaf design

    private Jsonl units, revisions, lots, suppliers, workOrders, events, edges;
    private int unitSeq = 0, woSeq = 0, lotSeq = 0;

    public Seed(Path canonical, Path out, long rngSeed, int fleetSize, List<Airframe> airframes) {
        this.canonical = canonical;
        this.out = out;
        this.rngSeed = rngSeed;
        this.fleetSize = fleetSize;
        this.airframes = airframes;
        this.rng = new Random(rngSeed);
    }

    public void run() throws Exception {
        List<Airframe> fleet = new ArrayList<>(airframes);
        java.util.Collections.shuffle(fleet, rng);
        fleet = fleet.subList(0, Math.min(fleetSize, fleet.size()));

        try (Jsonl u = new Jsonl(out.resolve("unit.jsonl"));
             Jsonl r = new Jsonl(out.resolve("revision.jsonl"));
             Jsonl l = new Jsonl(out.resolve("lot.jsonl"));
             Jsonl s = new Jsonl(out.resolve("supplier.jsonl"));
             Jsonl w = new Jsonl(out.resolve("work_order.jsonl"));
             Jsonl f = new Jsonl(out.resolve("failure_event.jsonl"));
             Jsonl e = new Jsonl(out.resolve("edges.jsonl"))) {
            units = u; revisions = r; lots = l; suppliers = s; workOrders = w; events = f; edges = e;

            for (int i = 1; i <= 12; i++) {
                suppliers.write(Jsonl.obj().put("label", "Supplier").put("id", String.format("SUP-%02d", i))
                        .put("name", "Supplier " + i).put("source", "seed"));
            }

            int idx = 0;
            for (Airframe af : fleet) {
                buildAircraft(af, idx++);
            }

            String badLot = unitsByLot.entrySet().stream()
                    .max(java.util.Comparator.comparingInt(en -> en.getValue().size())).orElseThrow().getKey();
            String badRev = designVariants.values().stream()
                    .filter(v -> v[1] != null).map(v -> v[0])  // superseded rev A with the most units built to it
                    .max(java.util.Comparator.comparingInt(a -> unitsByRev.getOrDefault(a, List.of()).size()))
                    .orElseThrow();

            List<String> clusterEvents = new ArrayList<>();
            int evSeq = 0;
            for (String unit : unitsByLot.get(badLot)) {
                if (rng.nextDouble() < 0.7) clusterEvents.add(failure(++evSeq, unit, "CRACKED"));
            }
            List<String> allSeedUnits = new ArrayList<>(parentOf.keySet());
            for (int i = 0; i < 30; i++) {
                failure(++evSeq, allSeedUnits.get(rng.nextInt(allSeedUnits.size())), "NOISE");
            }

            writeGroundTruth(badLot, badRev, clusterEvents);
            System.out.printf("seed: fleet %d, %d units, %d revisions, %d lots, %d work orders, %d events, %d edges%n",
                    fleet.size(), u.lines(), r.lines(), l.lines(), w.lines(), f.lines(), e.lines());
        }
        selfCheck();
    }

    private void buildAircraft(Airframe af, int idx) throws Exception {
        rootAsset.put(af.unitId(), af.assetId());
        String base = af.designRev().substring(af.designRev().lastIndexOf(':') + 1);
        for (String subsystem : SUBSYSTEMS) {
            String subRev;
            if (subsystem.equals("ENGINE") && af.engineRev() != null) {
                subRev = af.engineRev();
            } else {
                subRev = pickVariant(base + ":" + subsystem);
            }
            String subUnit = unit("assembly", subsystem, subRev, af.unitId(), idx);
            for (String leaf : LEAVES) {
                String leafRev = pickVariant(base + ":" + subsystem + ":" + leaf);
                String leafUnit = unit("component", subsystem + "/" + leaf, leafRev, subUnit, idx);
                madeFrom(leafUnit, base + ":" + subsystem + ":" + leaf);
            }
        }
    }

    /** Creates rev A (and sometimes a superseding rev B) for a synthetic design, once. */
    private String pickVariant(String designKey) throws Exception {
        String[] v = designVariants.get(designKey);
        if (v == null) {
            String a = "REV:SYN:" + designKey + ":A";
            String b = rng.nextDouble() < 0.3 ? "REV:SYN:" + designKey + ":B" : null;
            v = new String[]{a, b};
            designVariants.put(designKey, v);
            emitRevision(a, designKey, "A");
            if (b != null) {
                emitRevision(b, designKey, "B");
                edge("SUPERSEDED_BY", a, b);
            }
        }
        return v[1] != null && rng.nextDouble() < 0.5 ? v[1] : v[0];
    }

    private void emitRevision(String id, String designKey, String rev) throws Exception {
        revisions.write(Jsonl.obj().put("label", "Revision").put("id", id)
                .put("kind", "synthetic-design").put("design", designKey).put("rev", rev).put("source", "seed"));
    }

    private String unit(String kind, String name, String revId, String parent, int idx) throws Exception {
        String id = String.format("S-%06d", ++unitSeq);
        units.write(Jsonl.obj().put("label", "SerializedUnit").put("id", id)
                .put("kind", kind).put("name", name).put("source", "seed"));
        edge("BUILT_TO", id, revId);
        edge("COMPOSED_OF", parent, id);
        parentOf.put(id, parent);
        unitsByRev.computeIfAbsent(revId, k -> new ArrayList<>()).add(id);

        String wo = String.format("WO-%06d", ++woSeq);
        LocalDate start = LocalDate.of(2021, 1, 1).plusDays(idx);
        workOrders.write(Jsonl.obj().put("label", "WorkOrder").put("id", wo)
                .put("workCenter", "WC-" + (1 + woSeq % 6))
                .put("start", start.toString()).put("end", start.plusDays(3).toString()).put("source", "seed"));
        edge("PRODUCED_BY", id, wo);
        return id;
    }

    /** Lots roll over every LOT_BATCH uses per leaf design, each supplied by one supplier. */
    private void madeFrom(String unitId, String designKey) throws Exception {
        String lot = lotOf.get(designKey);
        int used = lotCursor.getOrDefault(designKey, 0);
        if (lot == null || used >= LOT_BATCH) {
            lot = String.format("LOT-%05d", ++lotSeq);
            lotOf.put(designKey, lot);
            lotCursor.put(designKey, 0);
            used = 0;
            String supplier = String.format("SUP-%02d", 1 + rng.nextInt(12));
            lots.write(Jsonl.obj().put("label", "MaterialLot").put("id", lot)
                    .put("design", designKey).put("source", "seed"));
            edge("SUPPLIED_BY", lot, supplier);
        }
        lotCursor.put(designKey, used + 1);
        edge("MADE_FROM", unitId, lot);
        unitsByLot.computeIfAbsent(lot, k -> new ArrayList<>()).add(unitId);
    }

    private String failure(int seq, String unitId, String mode) throws Exception {
        String id = String.format("SEED-F-%04d", seq);
        events.write(Jsonl.obj().put("label", "FailureEvent").put("id", id)
                .put("unitId", unitId).put("mode", mode)
                .put("date", LocalDate.of(2022, 6, 1).plusDays(seq % 90).toString()).put("source", "seed"));
        return id;
    }

    private void edge(String type, String from, String to) throws Exception {
        edges.write(Jsonl.obj().put("type", type).put("from", from).put("to", to));
    }

    private Set<String> climbToAssets(List<String> startUnits) {
        Set<String> assets = new TreeSet<>();
        for (String u : startUnits) {
            String cur = u;
            while (cur != null && !rootAsset.containsKey(cur)) cur = parentOf.get(cur);
            if (cur != null) assets.add(rootAsset.get(cur));
        }
        return assets;
    }

    private void writeGroundTruth(String badLot, String badRev, List<String> clusterEvents) throws Exception {
        ObjectNode gt = Jsonl.obj();
        gt.put("rngSeed", rngSeed);

        ObjectNode lot = gt.putObject("badLot");
        lot.put("lotId", badLot);
        fill(lot.putArray("units"), unitsByLot.get(badLot));
        fill(lot.putArray("expectedAffectedAssets"), climbToAssets(unitsByLot.get(badLot)));

        ObjectNode rev = gt.putObject("badRevision");
        rev.put("revisionId", badRev);
        List<String> revUnits = unitsByRev.getOrDefault(badRev, List.of());
        fill(rev.putArray("units"), revUnits);
        fill(rev.putArray("expectedAffectedAssets"), climbToAssets(revUnits));

        ObjectNode cluster = gt.putObject("failureCluster");
        fill(cluster.putArray("events"), clusterEvents);
        cluster.put("expectedRootCauseLot", badLot);

        Files.writeString(out.resolve("ground_truth.json"),
                Jsonl.M.writerWithDefaultPrettyPrinter().writeValueAsString(gt));
    }

    private static void fill(ArrayNode arr, Iterable<String> values) {
        for (String v : values) arr.add(v);
    }

    /** Recomputes the bad-lot blast radius from the emitted files and asserts it matches the manifest. */
    private void selfCheck() throws Exception {
        Map<String, String> parent = new HashMap<>();
        Map<String, List<String>> byLot = new HashMap<>();
        Map<String, String> installedIn = new HashMap<>();
        for (Path p : List.of(out.resolve("edges.jsonl"), canonical.resolve("edges.jsonl"))) {
            for (String line : Files.readAllLines(p)) {
                JsonNode e = Jsonl.M.readTree(line);
                switch (e.path("type").asText()) {
                    case "COMPOSED_OF" -> parent.put(e.path("to").asText(), e.path("from").asText());
                    case "MADE_FROM" -> byLot.computeIfAbsent(e.path("to").asText(), k -> new ArrayList<>()).add(e.path("from").asText());
                    case "INSTALLED_IN" -> installedIn.put(e.path("from").asText(), e.path("to").asText());
                }
            }
        }
        JsonNode gt = Jsonl.M.readTree(out.resolve("ground_truth.json").toFile());
        String badLot = gt.path("badLot").path("lotId").asText();
        Set<String> computed = new TreeSet<>();
        for (String u : byLot.getOrDefault(badLot, List.of())) {
            String cur = u;
            while (cur != null && !installedIn.containsKey(cur)) cur = parent.get(cur);
            if (cur != null) computed.add(installedIn.get(cur));
        }
        Set<String> expected = new TreeSet<>();
        gt.path("badLot").path("expectedAffectedAssets").forEach(n -> expected.add(n.asText()));
        if (!computed.equals(expected)) {
            throw new IllegalStateException("self-check FAILED: blast radius from files != manifest ("
                    + computed.size() + " vs " + expected.size() + ")");
        }
        System.out.printf("self-check PASS: bad lot %s hits %d assets, files agree with manifest%n",
                badLot, expected.size());
    }
}
