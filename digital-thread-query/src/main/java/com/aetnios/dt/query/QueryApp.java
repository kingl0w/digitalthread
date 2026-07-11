package com.aetnios.dt.query;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class QueryApp {

    public static void main(String[] args) {
        SpringApplication.run(QueryApp.class, args);
    }

    // per-request cost caps: the per-minute limit counts requests, so without these one request
    // packing hundreds of aliased heavy fields would sidestep it. Depth 15 / complexity 300 keep
    // GraphiQL's introspection query working (depth ~13, complexity ~200) with headroom.
    @Bean
    Instrumentation maxDepth() {
        return new MaxQueryDepthInstrumentation(15);
    }

    @Bean
    Instrumentation maxComplexity() {
        return new MaxQueryComplexityInstrumentation(300);
    }

    @Bean(destroyMethod = "close")
    Driver neo4j(@Value("${neo4j.uri}") String uri,
                 @Value("${neo4j.user}") String user,
                 @Value("${neo4j.password}") String password) {
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }
}
