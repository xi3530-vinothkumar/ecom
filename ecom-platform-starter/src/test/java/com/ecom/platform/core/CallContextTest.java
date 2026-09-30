package com.ecom.platform.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CallContextTest {

    @Test
    void nestedScopeRebindsAndRestoresContextAndMdc() {
        var outer = new CallContext("corr-1", null, Set.of());
        var inner = new CallContext("corr-1", "user-7", Set.of("CUSTOMER"));

        CallContext.run(outer, () -> {
            assertThat(MDC.get(CallContext.MDC_USER_ID)).isNull();
            CallContext.run(inner, () -> {
                assertThat(CallContext.current()).contains(inner);
                assertThat(MDC.get(CallContext.MDC_USER_ID)).isEqualTo("user-7");
            });
            assertThat(CallContext.current()).contains(outer);
            assertThat(MDC.get(CallContext.MDC_CORRELATION_ID)).isEqualTo("corr-1");
            assertThat(MDC.get(CallContext.MDC_USER_ID)).isNull();
        });

        assertThat(CallContext.current()).isEmpty();
        assertThat(MDC.get(CallContext.MDC_CORRELATION_ID)).isNull();
    }

    @Test
    void unbindsWhenTheBodyThrows() {
        assertThatThrownBy(() -> CallContext.run(new CallContext("c", "u", Set.of()), () -> {
            throw EcomException.notFound("x");
        })).isInstanceOf(EcomException.class);

        assertThat(CallContext.current()).isEmpty();
        assertThat(MDC.get(CallContext.MDC_USER_ID)).isNull();
    }
}
