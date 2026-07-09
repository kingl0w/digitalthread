package com.aetnios.dt.query;

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

    @Bean(destroyMethod = "close")
    Driver neo4j(@Value("${neo4j.uri}") String uri,
                 @Value("${neo4j.user}") String user,
                 @Value("${neo4j.password}") String password) {
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }
}
