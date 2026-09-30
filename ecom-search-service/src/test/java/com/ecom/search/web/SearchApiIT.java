package com.ecom.search.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecom.search.SearchIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchApiIT extends SearchIntegrationTest {

    private String category;
    private String word;
    private String zetaInStock;
    private String zetaOut;
    private String omega;

    @BeforeEach
    void seed() {
        category = id();
        // unique token so fuzzy matching never picks up other tests' documents
        var rnd = java.util.concurrent.ThreadLocalRandom.current();
        word = "zq" + rnd.ints(6, 'a', 'z' + 1).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append);
        zetaInStock = sku();
        zetaOut = sku();
        omega = sku();
        productChanged(product(id(), word + " Runner", 1, category, "Zeta", zetaInStock), true);
        productChanged(product(id(), word + " Walker", 1, category, "Zeta", zetaOut), true);
        productChanged(product(id(), word + " Hiker", 1, category, "Omega", omega), true);
        var inactive = new com.ecom.common.events.payload.ProductChangedPayload(id(), word + " Hidden", null, category,
                "Zeta", false, 1, product(id(), "x", 1, category, "Zeta", sku()).variants());
        productChanged(inactive, true);
        stock(zetaInStock, 3, 1);
        stock(zetaOut, 0, 1);
        stock(omega, 8, 1);
        awaitIndexed();
    }

    @Test
    void fullTextSearchWithFacets() throws Exception {
        mvc.perform(get("/search").param("q", word).param("categoryId", category))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].sku", containsInAnyOrder(zetaInStock, zetaOut, omega)))
                .andExpect(jsonPath("$.items[0].price.amount").value("19.99"))
                .andExpect(jsonPath("$.facets.brand[?(@.value == 'Zeta')].count").value(2))
                .andExpect(jsonPath("$.facets.brand[?(@.value == 'Omega')].count").value(1))
                .andExpect(jsonPath("$.facets.inStock[?(@.value == 'true')].count").value(2))
                .andExpect(jsonPath("$.facets.inStock[?(@.value == 'false')].count").value(1))
                .andExpect(jsonPath("$.facets.categoryId[0].value").value(category));
    }

    @Test
    void filtersNarrowHitsButKeepOwnFacetMultiSelect() throws Exception {
        mvc.perform(get("/search").param("categoryId", category).param("brand", "Zeta").param("inStock", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sku").value(zetaInStock))
                .andExpect(jsonPath("$.items[0].inStock").value(true))
                .andExpect(jsonPath("$.items[0].availableQty").value(3))
                // brand facet ignores the brand filter (but honours inStock): Zeta 1, Omega 1
                .andExpect(jsonPath("$.facets.brand[?(@.value == 'Omega')].count").value(1))
                .andExpect(jsonPath("$.facets.brand[?(@.value == 'Zeta')].count").value(1))
                // inStock facet ignores the inStock filter: Zeta true 1 / false 1
                .andExpect(jsonPath("$.facets.inStock[?(@.value == 'false')].count").value(1));
    }

    @Test
    void fuzzyMatchAndPaging() throws Exception {
        mvc.perform(get("/search").param("q", "runer").param("categoryId", category))
                .andExpect(jsonPath("$.items[*].sku", hasItem(zetaInStock)));
        mvc.perform(get("/search").param("categoryId", category).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/search").param("size", "1000")).andExpect(status().isBadRequest());
        mvc.perform(get("/search").param("inStock", "maybe")).andExpect(status().isBadRequest());
    }

    @Test
    void suggestReturnsDistinctVisibleNamesForPrefix() throws Exception {
        mvc.perform(get("/search/suggest").param("q", word.substring(0, 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions", containsInAnyOrder(
                        word + " Runner", word + " Walker", word + " Hiker")));
        mvc.perform(get("/search/suggest").param("q", word + " wal"))
                .andExpect(jsonPath("$.suggestions", containsInAnyOrder(word + " Walker")));
        mvc.perform(get("/search/suggest").param("q", " "))
                .andExpect(jsonPath("$.suggestions.length()").value(0));
    }

    @Test
    void inactiveProductsNeverSurface() throws Exception {
        mvc.perform(get("/search").param("q", word + " Hidden").param("categoryId", category))
                .andExpect(jsonPath("$.items[*].name", everyItem(is(org.hamcrest.Matchers.not(word + " Hidden")))));
    }
}
