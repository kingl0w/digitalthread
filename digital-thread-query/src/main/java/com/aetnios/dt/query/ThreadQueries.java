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
 * The two money queries as a read surface. Hand-written Cypher on the bare driver; the traversal
 * shapes fight OGM mapping, per the project decision in CLAUDE.md.
 */
@Controller
public class ThreadQueries {

    public record Asset(String id, String nNumber, String yearMfr) {}
    public record LotScore(String lotId, String supplierId, int hits) {}

    private static final String BLAST_BY_LOT = """
            MATCH (:MaterialLot {id: $id})<-[:MADE_FROM]-(u:SerializedUnit)
            MATCH (u)<-[:COMPOSED_OF*0..]-(root:SerializedUnit)-[:INSTALLED_IN]->(a:Asset)
            RETURN DISTINCT a.id AS id, a.nNumber AS nNumber, a.yearMfr AS yearMfr
            ORDER BY id
            """;

    private static final String BLAST_BY_REVISION = """
            MATCH (:Revision {id: $id})<-[:BUILT_TO]-(u:SerializedUnit)
            MATCH (u)<-[:COMPOSED_OF*0..]-(root:SerializedUnit)-[:INSTALLED_IN]->(a:Asset)
            RETURN DISTINCT a.id AS id, a.nNumber AS nNumber, a.yearMfr AS yearMfr
            ORDER BY id
            """;

    private static final String ROOT_CAUSE = """
            UNWIND $events AS eid
            MATCH (:FailureEvent {id: eid})-[:ON_UNIT]->(u:SerializedUnit)
            MATCH (u)-[:COMPOSED_OF*0..]->(leaf:SerializedUnit)-[:MADE_FROM]->(lot:MaterialLot)
            OPTIONAL MATCH (lot)-[:SUPPLIED_BY]->(s:Supplier)
            WITH lot, s, count(DISTINCT eid) AS hits
            RETURN lot.id AS lotId, s.id AS supplierId, hits
            ORDER BY hits DESC, lotId
            """;

    private final Driver driver;

    public ThreadQueries(Driver driver) {
        this.driver = driver;
    }

    // serial-ranged AFFECTS edges narrow to units in range (lexicographic — fixed-width serials)
    private static final String BLAST_BY_CAMPAIGN = """
            MATCH (:Campaign {id: $id})-[af:AFFECTS]->(:Revision)<-[:BUILT_TO]-(u:SerializedUnit)
            WHERE af.serialFrom IS NULL OR (u.serial >= af.serialFrom AND u.serial <= af.serialTo)
            MATCH (u)<-[:COMPOSED_OF*0..]-(root:SerializedUnit)-[:INSTALLED_IN]->(a:Asset)
            RETURN DISTINCT a.id AS id, a.nNumber AS nNumber, a.yearMfr AS yearMfr
            ORDER BY id
            """;

    private static final String TOP_CAMPAIGNS = """
            MATCH (c:Campaign)-[af:AFFECTS]->(r:Revision)<-[:BUILT_TO]-(u:SerializedUnit)-[:INSTALLED_IN]->(a:Asset)
            WHERE af.serialFrom IS NULL OR (u.serial >= af.serialFrom AND u.serial <= af.serialTo)
            WITH c, count(DISTINCT r) AS designs, count(DISTINCT a) AS fleet
            RETURN c.id AS id, c.title AS title, designs, fleet
            ORDER BY fleet DESC LIMIT $limit
            """;

    public record CampaignReach(String campaignId, String title, int designs, int fleet) {}

    public record GraphNode(String id, String label) {}
    public record GraphLink(String source, String target, String type) {}
    public record Subgraph(List<GraphNode> nodes, List<GraphLink> links) {}

    // same traversal as BLAST_BY_LOT, but keep the paths: distinct edges of the reached subgraph.
    // ponytail: LIMIT 2000 caps a public endpoint; paginate if a lot ever legitimately exceeds it
    private static final String BLAST_GRAPH = """
            MATCH p = (a:Asset)<-[:INSTALLED_IN]-(:SerializedUnit)-[:COMPOSED_OF*0..]->(u:SerializedUnit)-[:MADE_FROM]->(:MaterialLot {id: $id})
            UNWIND relationships(p) AS r
            WITH DISTINCT r
            RETURN startNode(r).id AS src, [l IN labels(startNode(r)) WHERE l <> 'Node'][0] AS srcLabel,
                   endNode(r).id AS dst, [l IN labels(endNode(r)) WHERE l <> 'Node'][0] AS dstLabel, type(r) AS type
            LIMIT 2000
            """;

    // the root-cause walk with its paths kept: every candidate lot stays visible, so the one
    // reached by all events stands out against the ones reached by few
    private static final String ROOT_GRAPH = """
            MATCH p = (f:FailureEvent)-[:ON_UNIT]->(:SerializedUnit)-[:COMPOSED_OF*0..]->(:SerializedUnit)-[:MADE_FROM]->(:MaterialLot)
            WHERE f.id IN $events
            UNWIND relationships(p) AS r
            WITH DISTINCT r
            RETURN startNode(r).id AS src, [l IN labels(startNode(r)) WHERE l <> 'Node'][0] AS srcLabel,
                   endNode(r).id AS dst, [l IN labels(endNode(r)) WHERE l <> 'Node'][0] AS dstLabel, type(r) AS type
            LIMIT 2000
            """;

    // one hop in any direction; LIMIT guards high-degree nodes (a Revision touches thousands of units)
    private static final String NEIGHBORS = """
            MATCH (n:Node {id: $id})-[r]-()
            WITH DISTINCT r LIMIT 200
            RETURN startNode(r).id AS src, [l IN labels(startNode(r)) WHERE l <> 'Node'][0] AS srcLabel,
                   endNode(r).id AS dst, [l IN labels(endNode(r)) WHERE l <> 'Node'][0] AS dstLabel, type(r) AS type
            """;

    @QueryMapping
    public Subgraph blastRadiusGraph(@Argument String lotId) {
        return subgraph(BLAST_GRAPH, Map.of("id", lotId));
    }

    @QueryMapping
    public Subgraph rootCauseGraph(@Argument List<String> eventIds) {
        if (eventIds.size() > 100) throw new IllegalArgumentException("at most 100 eventIds");
        return subgraph(ROOT_GRAPH, Map.of("events", eventIds));
    }

    @QueryMapping
    public Subgraph neighbors(@Argument String id) {
        return subgraph(NEIGHBORS, Map.of("id", id));
    }

    private Subgraph subgraph(String cypher, Map<String, Object> params) {
        try (Session session = driver.session()) {
            var nodes = new java.util.LinkedHashMap<String, GraphNode>();
            List<GraphLink> links = session.run(cypher, params).list(r -> {
                String src = r.get("src").asString(), dst = r.get("dst").asString();
                nodes.putIfAbsent(src, new GraphNode(src, r.get("srcLabel").asString()));
                nodes.putIfAbsent(dst, new GraphNode(dst, r.get("dstLabel").asString()));
                return new GraphLink(src, dst, r.get("type").asString());
            });
            return new Subgraph(List.copyOf(nodes.values()), links);
        }
    }

    @QueryMapping
    public List<Asset> blastRadiusByCampaign(@Argument String campaignId) {
        return assets(BLAST_BY_CAMPAIGN, campaignId);
    }

    @QueryMapping
    public List<CampaignReach> topCampaigns(@Argument int limit) {
        try (Session session = driver.session()) {
            return session.run(TOP_CAMPAIGNS, Map.of("limit", limit))
                    .list(r -> new CampaignReach(r.get("id").asString(), r.get("title").asString(null),
                            r.get("designs").asInt(), r.get("fleet").asInt()));
        }
    }

    @QueryMapping
    public List<Asset> blastRadiusByLot(@Argument String lotId) {
        return assets(BLAST_BY_LOT, lotId);
    }

    @QueryMapping
    public List<Asset> blastRadiusByRevision(@Argument String revisionId) {
        return assets(BLAST_BY_REVISION, revisionId);
    }

    @QueryMapping
    public List<LotScore> rootCause(@Argument List<String> eventIds) {
        // public endpoint: the id list is the one input a caller can scale up arbitrarily
        if (eventIds.size() > 100) throw new IllegalArgumentException("at most 100 eventIds");
        try (Session session = driver.session()) {
            return session.run(ROOT_CAUSE, Map.of("events", eventIds))
                    .list(r -> new LotScore(r.get("lotId").asString(),
                            r.get("supplierId").asString(null), r.get("hits").asInt()));
        }
    }

    @GraphQlExceptionHandler
    public GraphQLError badInput(IllegalArgumentException e) {
        return GraphQLError.newError().errorType(ErrorType.BAD_REQUEST).message(e.getMessage()).build();
    }

    private List<Asset> assets(String cypher, String id) {
        try (Session session = driver.session()) {
            return session.run(cypher, Map.of("id", id))
                    .list(r -> new Asset(r.get("id").asString(),
                            r.get("nNumber").asString(null), r.get("yearMfr").asString(null)));
        }
    }
}
