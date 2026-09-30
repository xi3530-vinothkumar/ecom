package com.ecom.search.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.search.domain.SearchDocument;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SearchControllerTest {

    @Test
    void mapsDocumentAndFormatsPrice() {
        var doc = new SearchDocument("S", "p", "Tee", null, "M", "Acme", "c", new BigDecimal("20"), "EUR",
                true, null, null, 1L, false, null, 1L);
        var item = SearchController.item(doc);
        assertThat(item.price().amount()).isEqualTo("20.00");
        assertThat(item.inStock()).isFalse();
        assertThat(item.availableQty()).isZero();
        assertThat(SearchController.plain(new BigDecimal("19.9950"))).isEqualTo("19.995");
    }
}
