package com.aetnios.dt.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Raw zone to canonical zone. Registry becomes Asset, airframe SerializedUnit, Revision,
 * BUILT_TO and INSTALLED_IN. SDRs become FailureEvent plus real COMPOSED_OF edges wherever the
 * report carries engine and part serials. ADs become Campaign. Never touches raw files.
 */
public class Transform {

    public record Airframe(String unitId, String assetId, String designRev, String engineRev) {}

    public record Result(List<Airframe> airframes) {}

    private static final Pattern TR = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[hd][^>]*>(.*?)</t[hd]>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private final Path raw;
    private final Path out;
    private final String makeFilter;

    private final Map<String, String[]> acftref = new HashMap<>();
    private final Map<String, String[]> engineRef = new HashMap<>();
    private final Map<String, String> engineByModel = new HashMap<>();
    private final Map<String, String> unitByAsset = new HashMap<>();
    private final Map<String, Set<String>> revsByModel = new HashMap<>();  // trimmed model text -> emitted airframe revs
    private final Set<String> seen = new HashSet<>();

    private Jsonl assets, units, revisions, events, campaigns, edges;

    public Transform(Path raw, Path out, String makeFilter) {
        this.raw = raw;
        this.out = out;
        this.makeFilter = makeFilter;
    }

    public Result run() throws Exception {
        List<Airframe> airframes = new ArrayList<>();
        try (Jsonl a = new Jsonl(out.resolve("asset.jsonl"));
             Jsonl u = new Jsonl(out.resolve("unit.jsonl"));
             Jsonl r = new Jsonl(out.resolve("revision.jsonl"));
             Jsonl f = new Jsonl(out.resolve("failure_event.jsonl"));
             Jsonl c = new Jsonl(out.resolve("campaign.jsonl"));
             Jsonl e = new Jsonl(out.resolve("edges.jsonl"))) {
            assets = a; units = u; revisions = r; events = f; campaigns = c; edges = e;
            registry(airframes);
            sdrs();
            ads();
            adTexts();
            System.out.printf("canonical: %d assets, %d units, %d revisions, %d events, %d campaigns, %d edges%n",
                    a.lines(), u.lines(), r.lines(), f.lines(), c.lines(), e.lines());
        }
        return new Result(airframes);
    }

    private void registry(List<Airframe> airframes) throws Exception {
        try (ZipFile zip = new ZipFile(raw.resolve("faa/registry/releasable_aircraft.zip").toFile())) {
            reference(zip, "ACFTREF.txt", acftref, 13);
            reference(zip, "ENGINE.txt", engineRef, 6);
            engineRef.forEach((code, row) -> engineByModel.putIfAbsent(norm(row[2]), code));

            int skipped = 0;
            ZipEntry master = zip.getEntry("MASTER.txt");
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(zip.getInputStream(master), StandardCharsets.ISO_8859_1))) {
                String line = br.readLine();  // header
                while ((line = br.readLine()) != null) {
                    String[] cols = line.split(",", -1);
                    if (cols.length < 34) { skipped++; continue; }
                    String code = cols[2].trim();
                    String[] design = acftref.get(code);
                    if (design == null || !design[1].trim().startsWith(makeFilter)) continue;

                    String nNumber = cols[0].trim();
                    String serial = cols[1].trim();
                    String assetId = "N" + nNumber;
                    String unitId = "AF:" + code + ":" + serial;
                    String revId = "REV:AC:" + code;
                    String engCode = cols[3].trim();
                    String engRevId = engineRev(engCode);

                    node(assets, "Asset", assetId, o -> o.put("nNumber", nNumber)
                            .put("yearMfr", cols[4].trim()).put("statusCode", cols[20].trim())
                            .put("airworthinessDate", cols[23].trim()).put("source", "faa/registry"));
                    node(units, "SerializedUnit", unitId, o -> o.put("kind", "airframe")
                            .put("serial", serial).put("source", "faa/registry"));
                    revision(revId, "airframe-design", design[1].trim(), design[2].trim(),
                            o -> o.put("tcDataSheet", design[11].trim()));
                    revsByModel.computeIfAbsent(design[2].trim(), k -> new HashSet<>()).add(revId);
                    edge("BUILT_TO", unitId, revId);
                    edge("INSTALLED_IN", unitId, assetId);
                    unitByAsset.put(assetId, unitId);
                    airframes.add(new Airframe(unitId, assetId, revId, engRevId));
                }
            }
            System.out.printf("registry: %d %s aircraft, %d malformed rows skipped%n",
                    airframes.size(), makeFilter, skipped);
        }
    }

    private void reference(ZipFile zip, String entry, Map<String, String[]> target, int cols) throws Exception {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(zip.getInputStream(zip.getEntry(entry)), StandardCharsets.ISO_8859_1))) {
            String line = br.readLine();  // header
            while ((line = br.readLine()) != null) {
                String[] c = line.split(",", -1);
                if (c.length >= cols) target.put(c[0].trim(), c);
            }
        }
    }

    private String engineRev(String code) throws Exception {
        String[] eng = engineRef.get(code);
        if (eng == null || eng[2].trim().isEmpty() || eng[2].trim().equals("NONE")) return null;
        String revId = "REV:EN:" + code;
        revision(revId, "engine-design", eng[1].trim(), eng[2].trim(),
                o -> o.put("horsepower", eng[4].trim()));
        return revId;
    }

    private void sdrs() throws Exception {
        int total = 0, assetHits = 0, assetMisses = 0, engineRevHits = 0, engineRevMisses = 0;
        List<Path> files = Files.list(raw.resolve("faa/sdr")).sorted().toList();
        for (Path file : files) {
            String html = Files.readString(file, StandardCharsets.ISO_8859_1);
            List<String> header = null;
            Matcher tr = TR.matcher(html);
            while (tr.find()) {
                List<String> cells = new ArrayList<>();
                Matcher cell = CELL.matcher(tr.group(1));
                while (cell.find()) {
                    cells.add(TAG.matcher(cell.group(1)).replaceAll("").replace("&nbsp;", "").trim());
                }
                if (header == null) { header = cells; continue; }
                Map<String, String> rec = new HashMap<>();
                for (int i = 0; i < header.size() && i < cells.size(); i++) rec.put(header.get(i), cells.get(i));
                total++;

                String nNumber = rec.getOrDefault("RegistryNNumber", "");
                String assetId = nNumber.isEmpty() ? null : "N" + nNumber;
                String airframeUnit = assetId == null ? null : unitByAsset.get(assetId);
                if (airframeUnit != null) assetHits++; else assetMisses++;

                String engSerial = rec.getOrDefault("EngineSerialNumber", "");
                String engineUnit = null;
                if (!engSerial.isEmpty()) {
                    engineUnit = "EN:" + engSerial;
                    node(units, "SerializedUnit", engineUnit, o -> o.put("kind", "engine")
                            .put("serial", engSerial)
                            .put("makeText", rec.getOrDefault("EngineMake", ""))
                            .put("modelText", rec.getOrDefault("EngineModel", ""))
                            .put("source", "faa/sdr"));
                    String engCode = engineByModel.get(norm(rec.getOrDefault("EngineModel", "")));
                    if (engCode != null) { edge("BUILT_TO", engineUnit, engineRev(engCode)); engineRevHits++; }
                    else engineRevMisses++;
                    if (airframeUnit != null) edge("COMPOSED_OF", airframeUnit, engineUnit);
                }

                String partSerial = rec.getOrDefault("PartSerialNumber", "");
                String partUnit = null;
                if (!partSerial.isEmpty()) {
                    partUnit = "PT:" + rec.getOrDefault("PartNumber", "UNKNOWN") + ":" + partSerial;
                    String pu = partUnit;
                    node(units, "SerializedUnit", pu, o -> o.put("kind", "part")
                            .put("serial", partSerial)
                            .put("partNumber", rec.getOrDefault("PartNumber", ""))
                            .put("nameText", rec.getOrDefault("PartName", ""))
                            .put("source", "faa/sdr"));
                    String parent = engineUnit != null ? engineUnit : airframeUnit;
                    if (parent != null) edge("COMPOSED_OF", parent, partUnit);
                }

                String subject = partUnit != null ? partUnit : engineUnit != null ? engineUnit : airframeUnit;
                ObjectNode ev = Jsonl.obj().put("label", "FailureEvent")
                        .put("id", rec.getOrDefault("OperatorControlNumber", ""))
                        .put("date", rec.getOrDefault("DifficultyDate", ""))
                        .put("jascCode", rec.getOrDefault("JASCCode", ""))
                        .put("natureOfCondition", rec.getOrDefault("NatureOfConditionA", ""))
                        .put("stageOfOperation", rec.getOrDefault("StageOfOperationCode", ""))
                        .put("partCondition", rec.getOrDefault("PartCondition", ""))
                        .put("partLocation", rec.getOrDefault("PartLocation", ""))
                        .put("discrepancy", rec.getOrDefault("Discrepancy", ""))
                        .put("unitId", subject)
                        .put("assetId", airframeUnit != null ? assetId : null)
                        // raw identity fields kept so downstream ingestion can re-resolve entities
                        .put("nNumber", nNumber)
                        .put("engineSerial", engSerial)
                        .put("partSerial", partSerial)
                        .put("partNumber", rec.getOrDefault("PartNumber", ""))
                        .put("source", "faa/sdr");
                events.write(ev);
            }
        }
        System.out.printf("sdr: %d events; asset resolution %d hit / %d miss; engine design %d hit / %d miss%n",
                total, assetHits, assetMisses, engineRevHits, engineRevMisses);
    }

    private void ads() throws Exception {
        int kept = 0, dropped = 0;
        List<Path> files = Files.list(raw.resolve("faa/ad")).sorted().toList();
        for (Path file : files) {
            JsonNode page = Jsonl.M.readTree(file.toFile());
            for (JsonNode d : page.path("results")) {
                String title = d.path("title").asText("");
                if (!title.startsWith("Airworthiness Directives")) { dropped++; continue; }
                kept++;
                campaigns.write(Jsonl.obj().put("label", "Campaign")
                        .put("id", d.path("document_number").asText())
                        .put("title", title)
                        .put("abstract", d.path("abstract").asText(""))
                        .put("publicationDate", d.path("publication_date").asText(""))
                        .put("htmlUrl", d.path("html_url").asText(""))
                        .put("source", "federalregister"));
            }
        }
        System.out.printf("ad: %d campaigns kept, %d non-AD rules dropped%n", kept, dropped);
    }

    /**
     * Links Campaigns to the airframe designs their rule text names. Matches known registry model
     * strings inside the AD's applicability section, word-bounded so 210 never matches 210G.
     * ponytail: model-level linking only; serial-range applicability is a later refinement.
     */
    private void adTexts() throws Exception {
        Path dir = raw.resolve("faa/ad_text");
        if (!Files.exists(dir)) return;
        int linked = 0, campaigns = 0, noMatch = 0;
        for (Path file : Files.list(dir).sorted().toList()) {
            String doc = file.getFileName().toString().replace(".txt", "").replace('_', '-');
            String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            Matcher section = Pattern.compile("\\(c\\) Applicability(.*?)\\n\\s*\\(d\\)", Pattern.DOTALL).matcher(text);
            String scopeText = section.find() ? section.group(1) : text;
            int before = linked;
            for (Map.Entry<String, Set<String>> en : revsByModel.entrySet()) {
                if (!Pattern.compile("\\b" + Pattern.quote(en.getKey()) + "\\b").matcher(scopeText).find()) continue;
                for (String revId : en.getValue()) {
                    edge("AFFECTS", doc, revId);
                    linked++;
                }
            }
            if (linked > before) campaigns++; else noMatch++;
        }
        System.out.printf("ad text: %d campaigns linked to designs via %d AFFECTS edges, %d texts matched no Cessna model%n",
                campaigns, linked, noMatch);
    }

    private interface Props { void apply(ObjectNode o); }

    private void node(Jsonl file, String label, String id, Props props) throws Exception {
        if (!seen.add(label + "|" + id)) return;
        ObjectNode o = Jsonl.obj().put("label", label).put("id", id);
        props.apply(o);
        file.write(o);
    }

    private void revision(String revId, String kind, String mfr, String model, Props props) throws Exception {
        node(revisions, "Revision", revId, o -> {
            o.put("kind", kind).put("mfr", mfr).put("model", model).put("source", "faa/registry");
            props.apply(o);
        });
    }

    private void edge(String type, String from, String to) throws Exception {
        if (to == null || from == null || !seen.add(type + "|" + from + "|" + to)) return;
        edges.write(Jsonl.obj().put("type", type).put("from", from).put("to", to));
    }

    private static String norm(String s) {
        return s.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }
}
