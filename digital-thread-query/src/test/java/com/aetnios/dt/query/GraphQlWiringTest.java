package com.aetnios.dt.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Schema-to-controller wiring against a mocked driver: catches field/argument drift between
 * schema.graphqls and the records in ThreadQueries (the exact bug class the smoke tests hit,
 * assetId vs id) without needing a Neo4j.
 */
@GraphQlTest({ThreadQueries.class, RagQueries.class})
class GraphQlWiringTest {

    @Autowired GraphQlTester tester;
    @MockBean Driver driver;
    @MockBean Embedder embedder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubEmptyResults() {
        Session session = mock(Session.class);
        Result result = mock(Result.class);
        when(driver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        doReturn(List.of()).when(result).list(any(Function.class));
        when(embedder.embed(anyString())).thenReturn(List.of(0.0));
    }

    @Test
    void everyQueryResolvesAgainstTheSchema() {
        tester.document("{ blastRadiusByLot(lotId: \"L\") { id nNumber yearMfr } }")
                .execute().path("blastRadiusByLot").entityList(Object.class).hasSize(0);
        tester.document("{ blastRadiusByRevision(revisionId: \"R\") { id } }")
                .execute().path("blastRadiusByRevision").entityList(Object.class).hasSize(0);
        tester.document("{ blastRadiusByCampaign(campaignId: \"C\") { id } }")
                .execute().path("blastRadiusByCampaign").entityList(Object.class).hasSize(0);
        tester.document("{ topCampaigns(limit: 3) { campaignId title designs fleet } }")
                .execute().path("topCampaigns").entityList(Object.class).hasSize(0);
        tester.document("{ rootCause(eventIds: [\"E\"]) { lotId supplierId hits } }")
                .execute().path("rootCause").entityList(Object.class).hasSize(0);
        tester.document("{ blastRadiusGraph(lotId: \"L\") { nodes { id label } links { source target type } } }")
                .execute().path("blastRadiusGraph.nodes").entityList(Object.class).hasSize(0);
        tester.document("{ rootCauseGraph(eventIds: [\"E\"]) { nodes { id label } links { source target type } } }")
                .execute().path("rootCauseGraph.nodes").entityList(Object.class).hasSize(0);
        tester.document("{ neighbors(id: \"N\") { nodes { id label } links { source target type } } }")
                .execute().path("neighbors.nodes").entityList(Object.class).hasSize(0);
        tester.document("{ similarFailures(text: \"crack\") { eventId score text nNumber } }")
                .execute().path("similarFailures").entityList(Object.class).hasSize(0);
        tester.document("{ investigate(text: \"crack\") { matches { eventId } rootCause { lotId hits } suspectLot blastRadius { id } } }")
                .execute().path("investigate.matches").entityList(Object.class).hasSize(0);
    }

    // non-null list types add a second bubbled-up error; assert on the typed one, not the count
    @Test
    void similarFailuresKOutOfRangeIsBadRequest() {
        tester.document("{ similarFailures(text: \"t\", k: 51) { eventId } }")
                .execute().errors().satisfy(errors ->
                        assertEquals(1, errors.stream()
                                .filter(e -> e.getErrorType() == ErrorType.BAD_REQUEST
                                        && "k must be 1..50".equals(e.getMessage())).count()));
    }

    @Test
    void disabledEmbedderIsCleanErrorNotServerError() {
        when(embedder.embed(anyString())).thenThrow(new IllegalStateException("semantic search is not enabled"));
        tester.document("{ similarFailures(text: \"t\") { eventId } }")
                .execute().errors().satisfy(errors ->
                        assertEquals(1, errors.stream()
                                .filter(e -> e.getErrorType() == ErrorType.FORBIDDEN
                                        && "semantic search is not enabled".equals(e.getMessage())).count()));
    }

    @Test
    void rootCauseOver100IdsIsBadRequestNotServerError() {
        String ids = String.join(",", Collections.nCopies(101, "\"x\""));
        tester.document("{ rootCause(eventIds: [" + ids + "]) { lotId } }")
                .execute().errors().satisfy(errors -> {
                    assertEquals(1, errors.size());
                    assertEquals(ErrorType.BAD_REQUEST, errors.get(0).getErrorType());
                    assertEquals("at most 100 eventIds", errors.get(0).getMessage());
                });
    }
}
