package com.ecom.user.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.user.proto.v1.GetAddressRequest;
import com.ecom.user.proto.v1.GetUserRequest;
import com.ecom.user.proto.v1.GetUserRolesRequest;
import com.ecom.user.proto.v1.UserServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

class UserGrpcIT extends AbstractIT {

    @Autowired GrpcServerLifecycle grpcServer;

    private ManagedChannel channel;
    private UserServiceGrpc.UserServiceBlockingStub stub;

    @BeforeEach
    void connect() {
        channel = NettyChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext().build();
        stub = UserServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void close() {
        channel.shutdownNow();
    }

    private String createAddress(UUID owner) {
        var res = http.exchange("/users/me/addresses", HttpMethod.POST,
                new HttpEntity<>(Map.of("line1", "Dam 1", "line2", "3rd floor", "city", "Amsterdam",
                        "postalCode", "1012JS", "countryCode", "NL"), asUser(owner, "CUSTOMER")), JSON);
        return (String) res.getBody().get("id");
    }

    @Test
    void getAddressReturnsAddressOnlyForItsOwner() {
        UUID owner = register(uniqueEmail(), "correct-horse-1");
        UUID other = register(uniqueEmail(), "correct-horse-1");
        String addressId = createAddress(owner);

        var address = stub.getAddress(GetAddressRequest.newBuilder()
                .setUserId(owner.toString()).setAddressId(addressId).build());
        assertThat(address.getAddressId()).isEqualTo(addressId);
        assertThat(address.getUserId()).isEqualTo(owner.toString());
        assertThat(address.getLine2()).isEqualTo("3rd floor");
        assertThat(address.getCountryCode()).isEqualTo("NL");

        assertThatThrownBy(() -> stub.getAddress(GetAddressRequest.newBuilder()
                        .setUserId(other.toString()).setAddressId(addressId).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    void getUserAndRoles() {
        String email = uniqueEmail();
        UUID id = register(email, "correct-horse-1");

        var user = stub.getUser(GetUserRequest.newBuilder().setUserId(id.toString()).build());
        assertThat(user.getEmail()).isEqualTo(email);
        assertThat(user.getFullName()).isEqualTo("Test User");
        assertThat(user.getPhone()).isEmpty();
        assertThat(user.getRolesList()).containsExactly("CUSTOMER");

        var roles = stub.getUserRoles(GetUserRolesRequest.newBuilder().setUserId(id.toString()).build());
        assertThat(roles.getRolesList()).containsExactly("CUSTOMER");
    }

    @Test
    void unknownOrMalformedIdsMapToGrpcStatuses() {
        assertThatThrownBy(() -> stub.getUser(GetUserRequest.newBuilder().setUserId(UUID.randomUUID().toString()).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
        assertThatThrownBy(() -> stub.getUser(GetUserRequest.newBuilder().setUserId("nope").build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }
}
