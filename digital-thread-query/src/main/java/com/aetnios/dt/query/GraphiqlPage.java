package com.aetnios.dt.query;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * The demo's two static pages, served from the classpath. The landing page is the public
 * showcase (runs the money queries live); /graphiql is our own GraphiQL instead of Spring's
 * bundled page, whose un-pinned unpkg assets break when the CDN's redirects drop CORS headers
 * (observed live). Ours pins jsDelivr URLs and preloads the money queries.
 */
@RestController
public class GraphiqlPage {

    private final String index;
    private final String graphiql;

    public GraphiqlPage() throws Exception {
        index = read("index.html");
        graphiql = read("graphiql.html");
    }

    private static String read(String name) throws Exception {
        return new String(new ClassPathResource(name).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() {
        return index;
    }

    @GetMapping(value = "/graphiql", produces = MediaType.TEXT_HTML_VALUE)
    public String graphiql() {
        return graphiql;
    }
}
