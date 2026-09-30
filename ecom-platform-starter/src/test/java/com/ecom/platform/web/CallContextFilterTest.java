package com.ecom.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.platform.core.CallContext;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CallContextFilterTest {

    @Test
    void populatesContextFromGatewayHeadersAndClearsAfter() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "u-1");
        req.addHeader("X-User-Roles", "CUSTOMER, ADMIN");
        req.addHeader("X-Correlation-Id", "not-a-uuid\nforged-log-line");
        var res = new MockHttpServletResponse();
        AtomicReference<CallContext> seen = new AtomicReference<>();

        new CallContextFilter().doFilter(req, res, (rq, rs) -> seen.set(CallContext.current().orElseThrow()));

        assertThat(seen.get().userId()).isEqualTo("u-1");
        assertThat(seen.get().roles()).containsExactlyInAnyOrder("CUSTOMER", "ADMIN");
        assertThat(seen.get().correlationId()).doesNotContain("forged").hasSize(36);
        assertThat(res.getHeader("X-Correlation-Id")).isEqualTo(seen.get().correlationId());
        assertThat(CallContext.current()).isEmpty();
    }
}
