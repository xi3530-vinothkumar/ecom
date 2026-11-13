package com.ecom.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecom.catalog.CatalogIntegrationTest;
import com.ecom.common.events.EventTopics;
import com.ecom.testing.Containers;
import com.ecom.testing.KafkaTestSupport;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AdminProductIT extends CatalogIntegrationTest {

    @Test
    void createUpdateShrinkDeleteBumpsVersionAndEmitsEachChange() throws Exception {
        String category = createCategory("Shirts " + sku());
        String skuA = sku();
        String skuB = sku();

        var created = read(mvc.perform(asAdmin(json(post("/admin/products"),
                        product("Tee", category, List.of(variant(skuA, "19.99"), variant(skuB, "24.50"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.variants.length()").value(2)));
        String id = created.path("id").asString();

        // price change on A -> version 2
        var update = new HashMap<>(product("Tee", category, List.of(variant(skuA, "17.00"), variant(skuB, "24.50"))));
        update.put("version", 1);
        mvc.perform(asAdmin(json(put("/admin/products/" + id), update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.variants[?(@.sku == '" + skuA + "')].price.amount").value("17.00"));

        // variant B removed -> version 3, B retired but still visible to admin as inactive
        mvc.perform(asAdmin(json(put("/admin/products/" + id), product("Tee", category, List.of(variant(skuA, "17.00"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.variants[?(@.sku == '" + skuB + "')].active").value(false));

        mvc.perform(get("/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].sku").value(skuA));

        mvc.perform(asAdmin(delete("/admin/products/" + id))).andExpect(status().isNoContent());
        mvc.perform(get("/products/" + id)).andExpect(status().isNotFound());
        mvc.perform(asAdmin(delete("/admin/products/" + id))).andExpect(status().isNotFound());

        Long dbVersion = jdbc.queryForObject("SELECT version FROM products WHERE id = ?::uuid", Long.class, id);
        assertThat(dbVersion).isEqualTo(4);

        List<JsonNode> events = outboxEvents(id);
        assertThat(events).extracting(e -> e.path("eventType").asString()).containsExactly(
                EventTopics.PRODUCT_CREATED, EventTopics.PRODUCT_UPDATED, EventTopics.PRODUCT_UPDATED,
                EventTopics.PRODUCT_DELETED);
        assertThat(events).extracting(e -> e.path("payload").path("version").asLong()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(events).allMatch(e -> e.path("producerService").asString().equals("catalog-service"));

        JsonNode priceChange = events.get(1).path("payload");
        assertThat(priceChange.path("variants")).hasSize(2);
        JsonNode aPrice = priceChange.path("variants").get(0).path("sku").asString().equals(skuA)
                ? priceChange.path("variants").get(0).path("price")
                : priceChange.path("variants").get(1).path("price");
        assertThat(aPrice.path("currencyCode").asString()).isEqualTo("EUR");
        assertThat(aPrice.path("units").asLong()).isEqualTo(17);

        assertThat(events.get(2).path("payload").path("variants")).hasSize(1);
        JsonNode deleted = events.get(3).path("payload");
        assertThat(deleted.path("skus")).extracting(JsonNode::asString).containsExactlyInAnyOrder(skuA, skuB);

        // relay publishes the rows to Kafka with productId as the key
        var relayed = KafkaTestSupport.await(Containers.kafka().getBootstrapServers(), mapper,
                EventTopics.PRODUCT_DELETED, e -> id.equals(e.payload().path("productId").asString()), Duration.ofSeconds(30));
        assertThat(relayed).hasSize(1);
    }

    @Test
    void staleExpectedVersionIsRejectedWithoutSideEffects() throws Exception {
        String category = createCategory("Stale " + sku());
        String id = createProduct(category, List.of(variant(sku(), "5")));
        var update = new HashMap<>(product("Renamed", category, List.of(variant(sku(), "5"))));
        update.put("version", 7);

        mvc.perform(asAdmin(json(put("/admin/products/" + id), update))).andExpect(status().isConflict());

        assertThat(outboxEvents(id)).hasSize(1);
        mvc.perform(asAdmin(get("/admin/products/" + id))).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void skuMustBeUniqueAcrossProducts() throws Exception {
        String category = createCategory("Dupes " + sku());
        String shared = sku();
        String first = createProduct(category, List.of(variant(shared, "5")));

        mvc.perform(asAdmin(json(post("/admin/products"), product("Other", category, List.of(variant(shared, "6"))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        String second = createProduct(category, List.of(variant(sku(), "6")));
        mvc.perform(asAdmin(json(put("/admin/products/" + second), product("Other", category, List.of(variant(shared, "6"))))))
                .andExpect(status().isConflict());
        assertThat(outboxEvents(first)).hasSize(1);
        assertThat(outboxEvents(second)).hasSize(1);
    }

    @Test
    void priceMustBeEurAndPositive() throws Exception {
        String category = createCategory("Prices " + sku());
        var usd = Map.of("sku", sku(), "variantName", "M", "price", Map.of("amount", "10", "currency", "USD"));
        mvc.perform(asAdmin(json(post("/admin/products"), product("Usd", category, List.of(usd)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("EUR")));
        mvc.perform(asAdmin(json(post("/admin/products"), product("Free", category, List.of(variant(sku(), "0"))))))
                .andExpect(status().isBadRequest());
        mvc.perform(asAdmin(json(post("/admin/products"), product("NoVariants", category, List.of()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownCategoryIsUnprocessable() throws Exception {
        mvc.perform(asAdmin(json(post("/admin/products"),
                        product("X", java.util.UUID.randomUUID().toString(), List.of(variant(sku(), "1"))))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void adminEndpointsRequireAdminRole() throws Exception {
        String category = createCategory("Guarded " + sku());
        var body = product("X", category, List.of(variant(sku(), "1")));

        mvc.perform(asCustomer(json(post("/admin/products"), body))).andExpect(status().isForbidden());
        mvc.perform(json(post("/admin/products"), body)).andExpect(status().isUnauthorized());
        // role check happens before body validation
        mvc.perform(asCustomer(json(post("/admin/products"), Map.of()))).andExpect(status().isForbidden());
        mvc.perform(asCustomer(json(post("/admin/categories"), Map.of("name", "x")))).andExpect(status().isForbidden());

        String id = createProduct(category, List.of(variant(sku(), "1")));
        mvc.perform(asCustomer(delete("/admin/products/" + id))).andExpect(status().isForbidden());
        mvc.perform(asCustomer(json(put("/admin/products/" + id), body))).andExpect(status().isForbidden());
        mvc.perform(get("/products/" + id)).andExpect(status().isOk());
        assertThat(outboxEvents(id)).hasSize(1);
    }

    @Test
    void publicListingShowsOnlyActiveProductsOfCategory() throws Exception {
        String category = createCategory("Listing " + sku());
        String visible = createProduct(category, List.of(variant(sku(), "9.5"), variant(sku(), "3")));
        var inactive = new HashMap<>(product("Hidden", category, List.of(variant(sku(), "1"))));
        inactive.put("active", false);
        mvc.perform(asAdmin(json(post("/admin/products"), inactive))).andExpect(status().isCreated());
        String deleted = createProduct(category, List.of(variant(sku(), "1")));
        mvc.perform(asAdmin(delete("/admin/products/" + deleted))).andExpect(status().isNoContent());

        mvc.perform(get("/products").param("categoryId", category).param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(visible))
                .andExpect(jsonPath("$.items[0].fromPrice.amount").value("3.00"));

        mvc.perform(get("/products").param("size", "500")).andExpect(status().isBadRequest());
    }

    @Test
    void categoriesCanBeCreatedMovedAndListed() throws Exception {
        String parent = createCategory("Parent " + sku());
        String child = read(mvc.perform(asAdmin(json(post("/admin/categories"), Map.of("name", "Child " + sku(), "parentId", parent))))
                .andExpect(status().isCreated())).path("id").asString();

        mvc.perform(asAdmin(json(put("/admin/categories/" + parent), Map.of("name", "Parent", "parentId", child))))
                .andExpect(status().isBadRequest());
        String name = "Dup " + sku();
        createCategory(name);
        mvc.perform(asAdmin(json(post("/admin/categories"), Map.of("name", name)))).andExpect(status().isConflict());

        mvc.perform(get("/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + child + "')].parentId").value(parent));
    }

    @Test
    void imageUploadIsUnavailableWhenImagesDisabled() throws Exception {
        String id = createProduct(createCategory("Img " + sku()), List.of(variant(sku(), "1")));
        mvc.perform(asAdmin(json(post("/admin/products/" + id + "/images/upload-url"), Map.of("contentType", "image/png"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("IMAGES_ENABLED")));
    }
}
