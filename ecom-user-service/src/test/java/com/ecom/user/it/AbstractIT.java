package com.ecom.user.it;

import static com.ecom.testing.RestCalls.exchange;

import com.ecom.testing.ContainerConfigs;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecom.cart.proto.v1.Cart;
import com.ecom.cart.proto.v1.CartServiceGrpc;
import com.ecom.cart.proto.v1.MergeCartRequest;
import com.ecom.platform.core.CallContext;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpCookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** One shared context for all ITs: Postgres container + a fake Cart gRPC server on a free port. */
@Import({ContainerConfigs.Postgres.class, ContainerConfigs.Kafka.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureRestTestClient
abstract class AbstractIT {

    static final String ADMIN_EMAIL = "admin@ecom.test";
    static final String ADMIN_PASSWORD = "admin-password-123";
    static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};

    record MergeCall(MergeCartRequest request, String propagatedUserId) {}

    static final List<MergeCall> MERGE_CALLS = new CopyOnWriteArrayList<>();
    static final Server FAKE_CART = startFakeCart();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.grpc.server.port", () -> "0");
        r.add("spring.grpc.client.channel.cart.target", () -> "localhost:" + FAKE_CART.getPort());
        r.add("ecom.outbox.relay-enabled", () -> "false");
        r.add("ecom.auth.ephemeral-jwt-key", () -> "true");
        r.add("ecom.auth.admin-email", () -> ADMIN_EMAIL);
        r.add("ecom.auth.admin-password", () -> ADMIN_PASSWORD);
    }

    @Autowired RestTestClient http;

    private static Server startFakeCart() {
        var impl = new CartServiceGrpc.CartServiceImplBase() {
            @Override
            public void mergeCart(MergeCartRequest request, StreamObserver<Cart> out) {
                MERGE_CALLS.add(new MergeCall(request, CallContext.current().map(CallContext::userId).orElse(null)));
                out.onNext(Cart.newBuilder().setUserId(request.getUserId()).build());
                out.onCompleted();
            }
        };
        try {
            return NettyServerBuilder.forPort(0)
                    .addService(io.grpc.ServerInterceptors.intercept(
                            impl, new com.ecom.platform.grpc.CallContextServerInterceptor()))
                    .build()
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String uniqueEmail() {
        return "u-" + UUID.randomUUID() + "@example.com";
    }

    UUID register(String email, String password) {
        ResponseEntity<Map<String, Object>> res = post("/users", Map.of(
                "email", email, "password", password, "fullName", "Test User"), new HttpHeaders());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) res.getBody().get("id"));
    }

    ResponseEntity<Map<String, Object>> login(String email, String password) {
        return post("/auth/login", Map.of("email", email, "password", password), new HttpHeaders());
    }

    ResponseEntity<Map<String, Object>> post(String path, Object body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return exchange(http, HttpMethod.POST, path, body, headers, JSON);
    }

    ResponseEntity<Map<String, Object>> withRefreshCookie(String path, String refreshToken) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, "refresh_token=" + refreshToken);
        return exchange(http, HttpMethod.POST, path, null, h, JSON);
    }

    static HttpCookie refreshCookie(ResponseEntity<?> res) {
        return res.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .flatMap(h -> HttpCookie.parse(h).stream())
                .filter(c -> c.getName().equals("refresh_token"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no refresh_token cookie"));
    }

    static HttpHeaders asUser(UUID userId, String roles) {
        HttpHeaders h = new HttpHeaders();
        h.add(CallContext.HEADER_USER_ID, userId.toString());
        h.add(CallContext.HEADER_USER_ROLES, roles);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
