package com.ecom.user.it;

import static com.ecom.testing.RestCalls.exchange;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ProfileAndAddressIT extends AbstractIT {

    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, Object body, HttpHeaders h) {
        return exchange(http, method, path, body, h, JSON);
    }

    static Map<String, Object> address(String city) {
        return Map.of("line1", "Main St 1", "city", city, "postalCode", "1011AB", "countryCode", "nl");
    }

    @Test
    void meRequiresGatewayIdentity() {
        assertThat(exchange(http, HttpMethod.GET, "/users/me", null, new HttpHeaders(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getAndPatchProfile() {
        String email = uniqueEmail();
        UUID id = register(email, "correct-horse-1");
        HttpHeaders me = asUser(id, "CUSTOMER");

        var got = call(HttpMethod.GET, "/users/me", null, me);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(got.getBody()).containsEntry("email", email).containsEntry("fullName", "Test User");

        var patched = call(HttpMethod.PATCH, "/users/me", Map.of("fullName", "New Name", "phone", "+31 20 123 4567"), me);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody()).containsEntry("fullName", "New Name").containsEntry("phone", "+31 20 123 4567")
                .containsEntry("email", email);

        var cleared = call(HttpMethod.PATCH, "/users/me", Map.of("phone", ""), me);
        assertThat(cleared.getBody()).containsEntry("fullName", "New Name").doesNotContainKey("phone");

        assertThat(call(HttpMethod.PATCH, "/users/me", Map.of("fullName", "  "), me).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void addressCrudIsOwnerScoped() {
        UUID alice = register(uniqueEmail(), "correct-horse-1");
        UUID bob = register(uniqueEmail(), "correct-horse-1");
        HttpHeaders asAlice = asUser(alice, "CUSTOMER");
        HttpHeaders asBob = asUser(bob, "CUSTOMER");

        var created = call(HttpMethod.POST, "/users/me/addresses", address("Amsterdam"), asAlice);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("countryCode", "NL");
        String addressId = (String) created.getBody().get("id");
        assertThat(created.getHeaders().getLocation()).hasToString("/users/me/addresses/" + addressId);

        var updated = call(HttpMethod.PUT, "/users/me/addresses/" + addressId, address("Utrecht"), asAlice);
        assertThat(updated.getBody()).containsEntry("city", "Utrecht");

        ResponseEntity<List> list = exchange(http, HttpMethod.GET, "/users/me/addresses", null, asAlice, List.class);
        assertThat(list.getBody()).hasSize(1);

        // Bob cannot see, change or delete Alice's address
        assertThat(call(HttpMethod.GET, "/users/me/addresses/" + addressId, null, asBob).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT, "/users/me/addresses/" + addressId, address("Evil"), asBob).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE, "/users/me/addresses/" + addressId, null, asBob).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(http, HttpMethod.GET, "/users/me/addresses", null, asBob, List.class).getBody())
                .isEmpty();

        assertThat(call(HttpMethod.DELETE, "/users/me/addresses/" + addressId, null, asAlice).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.GET, "/users/me/addresses/" + addressId, null, asAlice).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidAddressIsBadRequest() {
        UUID id = register(uniqueEmail(), "correct-horse-1");
        var res = call(HttpMethod.POST, "/users/me/addresses",
                Map.of("line1", "x", "city", "y", "postalCode", "z", "countryCode", "NLD"), asUser(id, "CUSTOMER"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
