package com.ecom.testing;

import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * {@link RestTestClient} calls that hand back a {@link ResponseEntity}, for ITs that assert on status,
 * headers and body separately. Never throws on 4xx/5xx.
 */
public final class RestCalls {

    public static <T> ResponseEntity<T> exchange(
            RestTestClient client, HttpMethod method, String path, @Nullable Object body, HttpHeaders headers, Class<T> type) {
        return entity(spec(client, method, path, body, headers).returnResult(type));
    }

    public static <T> ResponseEntity<T> exchange(
            RestTestClient client, HttpMethod method, String path, @Nullable Object body, HttpHeaders headers,
            ParameterizedTypeReference<T> type) {
        return entity(spec(client, method, path, body, headers).returnResult(type));
    }

    private static RestTestClient.ResponseSpec spec(
            RestTestClient client, HttpMethod method, String path, @Nullable Object body, HttpHeaders headers) {
        var request = client.method(method).uri(path).headers(h -> h.addAll(headers));
        return body == null ? request.exchange() : request.body(body).exchange();
    }

    private static <T> ResponseEntity<T> entity(EntityExchangeResult<T> result) {
        return ResponseEntity.status(result.getStatus())
                .headers(result.getResponseHeaders())
                .body(result.getResponseBody());
    }

    private RestCalls() {}
}
