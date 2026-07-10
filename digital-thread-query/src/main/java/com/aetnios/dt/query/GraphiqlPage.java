package com.aetnios.dt.query;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Serves our own GraphiQL page instead of Spring's bundled one, whose un-pinned unpkg assets
 * break when the CDN's redirects drop CORS headers (observed live). Ours pins jsDelivr URLs
 * and preloads the money queries.
 */
@RestController
public class GraphiqlPage {

    private final String html;

    public GraphiqlPage() throws Exception {
        html = new String(new ClassPathResource("graphiql.html").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/graphiql", produces = MediaType.TEXT_HTML_VALUE)
    public String page() {
        return html;
    }
}
