package com.ecom.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecom.platform.core.CallContext;
import com.ecom.testing.Containers;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Shared context for all catalog ITs (same config => one cached Spring context). */
@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
public abstract class CatalogIntegrationTest {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry r) {
        Containers.registerPostgres(r);
        Containers.registerKafka(r);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected JdbcTemplate jdbc;

    protected static <T extends MockHttpServletRequestBuilder> T asAdmin(T req) {
        req.header(CallContext.HEADER_USER_ID, "admin-" + UUID.randomUUID())
                .header(CallContext.HEADER_USER_ROLES, "CUSTOMER,ADMIN");
        return req;
    }

    protected static <T extends MockHttpServletRequestBuilder> T asCustomer(T req) {
        req.header(CallContext.HEADER_USER_ID, "user-" + UUID.randomUUID()).header(CallContext.HEADER_USER_ROLES, "CUSTOMER");
        return req;
    }

    protected MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, Object body) throws Exception {
        return req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    protected JsonNode read(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected String createCategory(String name) throws Exception {
        var res = mvc.perform(asAdmin(json(post("/admin/categories"), Map.of("name", name))))
                .andExpect(status().isCreated());
        return read(res).path("id").asString();
    }

    protected static String sku() {
        return "SKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    protected static Map<String, Object> variant(String sku, String amount) {
        return Map.of("sku", sku, "variantName", "Size " + sku.substring(4, 6),
                "price", Map.of("amount", amount, "currency", "EUR"));
    }

    protected static Map<String, Object> product(String name, String categoryId, List<Map<String, Object>> variants) {
        return Map.of("name", name, "description", "About " + name, "categoryId", categoryId, "brand", "Acme",
                "variants", variants);
    }

    protected String createProduct(String categoryId, List<Map<String, Object>> variants) throws Exception {
        var res = mvc.perform(asAdmin(json(post("/admin/products"), product("Prod " + UUID.randomUUID(), categoryId, variants))))
                .andExpect(status().isCreated());
        return read(res).path("id").asString();
    }

    /** Outbox rows for one product, oldest first, as parsed envelopes. */
    protected List<JsonNode> outboxEvents(String productId) {
        return jdbc.query("SELECT payload FROM outbox WHERE message_key = ? ORDER BY id",
                (rs, i) -> {
                    try {
                        return mapper.readTree(rs.getString(1));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                productId);
    }
}
