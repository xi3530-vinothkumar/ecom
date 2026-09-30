package com.ecom.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import org.junit.jupiter.api.Test;

class SearchQueryTest {

    @Test
    void appliesDefaultsAndNormalizesBlanks() {
        var q = SearchQuery.of("  shirt ", " ", "", null, null, null);
        assertThat(q.text()).isEqualTo("shirt");
        assertThat(q.categoryId()).isNull();
        assertThat(q.brand()).isNull();
        assertThat(q.page()).isZero();
        assertThat(q.size()).isEqualTo(20);
        assertThat(SearchQuery.of(null, null, null, true, 3, 10).from()).isEqualTo(30);
    }

    @Test
    void rejectsOutOfRangePaging() {
        assertValidation(() -> SearchQuery.of(null, null, null, null, -1, 10));
        assertValidation(() -> SearchQuery.of(null, null, null, null, 0, 0));
        assertValidation(() -> SearchQuery.of(null, null, null, null, 0, 101));
        assertValidation(() -> SearchQuery.of(null, null, null, null, 100, 100));
    }

    @Test
    void rejectsOverlongText() {
        assertValidation(() -> SearchQuery.of("x".repeat(201), null, null, null, 0, 10));
    }

    private static void assertValidation(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(EcomException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION));
    }
}
