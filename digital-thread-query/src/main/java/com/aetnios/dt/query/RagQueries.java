package com.aetnios.dt.query;

import graphql.GraphQLError;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;

/**
 * Hybrid retrieval: a free-text failure description enters the graph through vector search over
 * embedded SDR/seed narratives, then the exact lineage traversals take over. The LLM (or human)
 * only picks the entry point; the answer is deterministic Cypher with provenance, not generation.
 */
@Controller
public class RagQueries {

    public record FailureMatch(String eventId, double score, String text, String nNumber) {}
    public record Investigation(List<FailureMatch> matches, List<ThreadQueries.LotScore> rootCause,
                                String suspectLot, List<ThreadQueries.Asset> blastRadius) {}

    private static final String SIMILAR = """
            CALL db.index.vector.queryNodes('failure_text', $k, $vec) YIELD node, score
            RETURN node.id AS id, score,
                   trim(coalesce(node.partLocation, '') + ' ' + coalesce(node.partCondition, '')
                        + ' ' + coalesce(node.discrepancy, node.mode, '')) AS text,
                   node.nNumber AS nNumber
            """;

    private final Driver driver;
    private final Embedder embedder;
    private final ThreadQueries threads;

    public RagQueries(Driver driver, Embedder embedder, ThreadQueries threads) {
        this.driver = driver;
        this.embedder = embedder;
        this.threads = threads;
    }

    @QueryMapping
    public List<FailureMatch> similarFailures(@Argument String text, @Argument int k) {
        if (k < 1 || k > 50) throw new IllegalArgumentException("k must be 1..50");
        List<Double> vec = embedder.embed(text);
        try (Session session = driver.session()) {
            return session.run(SIMILAR, Map.of("k", k, "vec", vec))
                    .list(r -> new FailureMatch(r.get("id").asString(), r.get("score").asDouble(),
                            r.get("text").asString(null), r.get("nNumber").asString(null)));
        }
    }

    /** Fuzzy in, exact out: similar failures -> converging lots -> blast radius of the top suspect. */
    @QueryMapping
    public Investigation investigate(@Argument String text, @Argument int k) {
        List<FailureMatch> matches = similarFailures(text, k);
        List<ThreadQueries.LotScore> lots = matches.isEmpty() ? List.of()
                : threads.rootCause(matches.stream().map(FailureMatch::eventId).toList());
        String suspect = lots.isEmpty() ? null : lots.get(0).lotId();
        List<ThreadQueries.Asset> blast = suspect == null ? List.of() : threads.blastRadiusByLot(suspect);
        return new Investigation(matches, lots, suspect, blast);
    }

    @GraphQlExceptionHandler
    public GraphQLError badInput(IllegalArgumentException e) {
        return GraphQLError.newError().errorType(ErrorType.BAD_REQUEST).message(e.getMessage()).build();
    }

    @GraphQlExceptionHandler
    public GraphQLError unavailable(IllegalStateException e) {
        return GraphQLError.newError().errorType(ErrorType.FORBIDDEN).message(e.getMessage()).build();
    }
}
