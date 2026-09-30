package com.ecom.user.grpc;

import com.ecom.platform.core.EcomException;
import com.ecom.user.application.AddressService;
import com.ecom.user.application.ProfileService;
import com.ecom.user.proto.v1.Address;
import com.ecom.user.proto.v1.GetAddressRequest;
import com.ecom.user.proto.v1.GetUserRequest;
import com.ecom.user.proto.v1.GetUserRolesRequest;
import com.ecom.user.proto.v1.GetUserRolesResponse;
import com.ecom.user.proto.v1.User;
import com.ecom.user.proto.v1.UserServiceGrpc;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Thin proto mapping; errors are EcomExceptions translated to gRPC status by the platform interceptor. */
@Component
class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {

    private final ProfileService profiles;
    private final AddressService addresses;

    UserGrpcService(ProfileService profiles, AddressService addresses) {
        this.profiles = profiles;
        this.addresses = addresses;
    }

    @Override
    public void getUser(GetUserRequest request, StreamObserver<User> out) {
        var user = profiles.get(uuid(request.getUserId(), "user_id"));
        out.onNext(User.newBuilder()
                .setUserId(user.getId().toString())
                .setEmail(user.getEmail())
                .setFullName(user.getFullName())
                .setPhone(Objects.requireNonNullElse(user.getPhone(), ""))
                .addAllRoles(new TreeSet<>(user.getRoles()))
                .build());
        out.onCompleted();
    }

    @Override
    public void getUserRoles(GetUserRolesRequest request, StreamObserver<GetUserRolesResponse> out) {
        var user = profiles.get(uuid(request.getUserId(), "user_id"));
        out.onNext(GetUserRolesResponse.newBuilder().addAllRoles(new TreeSet<>(user.getRoles())).build());
        out.onCompleted();
    }

    @Override
    public void getAddress(GetAddressRequest request, StreamObserver<Address> out) {
        var a = addresses.get(uuid(request.getUserId(), "user_id"), uuid(request.getAddressId(), "address_id"));
        out.onNext(Address.newBuilder()
                .setAddressId(a.getId().toString())
                .setUserId(a.getUserId().toString())
                .setLine1(a.getLine1())
                .setLine2(Objects.requireNonNullElse(a.getLine2(), ""))
                .setCity(a.getCity())
                .setPostalCode(a.getPostalCode())
                .setCountryCode(a.getCountryCode())
                .build());
        out.onCompleted();
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw EcomException.validation(field + " must be a UUID");
        }
    }
}
