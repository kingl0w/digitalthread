package com.aetnios.dt.query;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitFilterTest {

    // pinned window: the test never races a real minute boundary
    private final RateLimitFilter filter = new RateLimitFilter() {
        @Override long minuteNow() { return 42; }
    };

    private int hit(String path, String client) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setRequestURI(path);
        if (client != null) req.addHeader("X-Forwarded-For", client);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void thirtyFirstGraphqlRequestIs429() throws Exception {
        for (int i = 1; i <= 30; i++) assertEquals(200, hit("/graphql", "1.2.3.4"), "request " + i);
        assertEquals(429, hit("/graphql", "1.2.3.4"));
    }

    @Test
    void limitIsPerClient() throws Exception {
        for (int i = 0; i <= 30; i++) hit("/graphql", "1.2.3.4");
        assertEquals(200, hit("/graphql", "5.6.7.8"));
    }

    @Test
    void firstForwardedHopIsTheClient() throws Exception {
        for (int i = 0; i <= 30; i++) hit("/graphql", "1.2.3.4, 10.0.0.1");
        assertEquals(429, hit("/graphql", "1.2.3.4, 10.9.9.9"));
    }

    @Test
    void nonGraphqlPathsAreExempt() throws Exception {
        for (int i = 0; i <= 30; i++) hit("/graphql", "1.2.3.4");
        assertEquals(200, hit("/actuator/health", "1.2.3.4"));
    }
}
