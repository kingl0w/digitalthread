package com.aetnios.dt.ingest;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Build order step 3: the event-driven write service. Run plain for the consumer; run with
 * --replay to publish the canonical failure events onto Kafka (stripped of resolved ids, so the
 * consumer has to do the entity resolution itself) and exit.
 */
@SpringBootApplication
public class IngestApp {

    public static void main(String[] args) {
        SpringApplication.run(IngestApp.class, args);
    }

    @Bean(destroyMethod = "close")
    Driver neo4j(@Value("${neo4j.uri}") String uri,
                 @Value("${neo4j.user}") String user,
                 @Value("${neo4j.password}") String password) {
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }
}
