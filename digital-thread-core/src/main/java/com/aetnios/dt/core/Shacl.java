package com.aetnios.dt.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.vocabulary.RDF;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Projects the canonical+seed JSONL zones into an RDF graph and validates it against
 * shapes.ttl (the schema as SHACL). Catches missing identity, dangling edges, and
 * edges whose endpoint is the wrong node type — before anything touches Neo4j.
 */
public final class Shacl {

    private static final String NS = "urn:dt:";
    private static final int REPORT_LIMIT = 10;

    private Shacl() {}

    public static boolean validate(Path canonical, Path seed) throws Exception {
        Model m = ModelFactory.createDefaultModel();
        Map<String, Property> props = new HashMap<>();

        for (Path dir : List.of(canonical, seed)) {
            for (String file : List.of("asset", "unit", "revision", "failure_event", "campaign",
                    "lot", "supplier", "work_order")) {
                Path p = dir.resolve(file + ".jsonl");
                if (!Files.exists(p)) continue;
                for (String line : Files.readAllLines(p)) {
                    JsonNode n = Jsonl.M.readTree(line);
                    Resource r = m.createResource(uri(n.path("id").asText()));
                    r.addProperty(RDF.type, m.createResource(NS + n.path("label").asText()));
                    Iterator<String> it = n.fieldNames();
                    while (it.hasNext()) {
                        String k = it.next();
                        String v = n.path(k).asText("");
                        if (k.equals("label") || v.isEmpty()) continue;
                        r.addProperty(props.computeIfAbsent(k, key -> m.createProperty(NS + key)), v);
                    }
                }
            }
            Path edges = dir.resolve("edges.jsonl");
            if (!Files.exists(edges)) continue;
            for (String line : Files.readAllLines(edges)) {
                JsonNode e = Jsonl.M.readTree(line);
                m.createResource(uri(e.path("from").asText()))
                        .addProperty(props.computeIfAbsent(e.path("type").asText(),
                                key -> m.createProperty(NS + key)),
                                m.createResource(uri(e.path("to").asText())));
            }
        }

        Model shapesModel = ModelFactory.createDefaultModel();
        try (InputStream in = Shacl.class.getResourceAsStream("/shapes.ttl")) {
            RDFDataMgr.read(shapesModel, in, Lang.TURTLE);
        }
        ValidationReport report = ShaclValidator.get()
                .validate(Shapes.parse(shapesModel.getGraph()), m.getGraph());
        if (!report.conforms()) {
            report.getEntries().stream().limit(REPORT_LIMIT).forEach(e ->
                    System.out.printf("SHACL violation: %s %s%n", e.focusNode(), e.message()));
        }
        System.out.printf("%s SHACL: %d triples validated, %d violations%n",
                report.conforms() ? "PASS" : "FAIL", m.size(), report.getEntries().size());
        return report.conforms();
    }

    private static String uri(String id) {
        return NS + URLEncoder.encode(id, StandardCharsets.UTF_8);
    }
}
