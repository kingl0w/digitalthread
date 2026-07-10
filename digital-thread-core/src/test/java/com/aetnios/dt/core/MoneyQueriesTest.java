package com.aetnios.dt.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The full pipeline against a real throwaway Neo4j: SHACL-validate the zones, load,
 * and assert both money queries plus bitemporal coverage against ground_truth.json.
 * Skips (does not fail) when the data zones haven't been generated.
 */
@Testcontainers
class MoneyQueriesTest {

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5");

    @Test
    void moneyQueriesPassAgainstGroundTruth() throws Exception {
        Path canonical = Path.of("data/canonical");
        Path seed = Path.of("data/seed");
        Assumptions.assumeTrue(Files.exists(seed.resolve("ground_truth.json")),
                "data zones not generated; run CoreApp first");

        assertTrue(GraphApp.run(neo4j.getBoltUrl(), "neo4j", neo4j.getAdminPassword(), canonical, seed),
                "SHACL + money queries + bitemporal coverage must all pass");
    }
}
