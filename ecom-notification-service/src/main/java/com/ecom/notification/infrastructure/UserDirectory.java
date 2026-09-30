package com.ecom.notification.infrastructure;

import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import com.ecom.platform.grpc.GrpcChannels;
import com.ecom.platform.grpc.ResilientCalls;
import com.ecom.user.proto.v1.GetUserRequest;
import com.ecom.user.proto.v1.User;
import com.ecom.user.proto.v1.UserServiceGrpc;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Contact details are fetched at send time (never carried in events), so erasure in userdb is enough. */
@Component
public class UserDirectory {

    private static final String USER = "user";

    private final ResilientCalls calls;
    private final UserServiceGrpc.UserServiceBlockingStub stub;

    public UserDirectory(GrpcChannels channels, ResilientCalls calls) {
        this.calls = calls;
        this.stub = UserServiceGrpc.newBlockingStub(channels.channel(USER));
    }

    /** Empty when the user no longer exists; other failures propagate (and are retried). */
    public Optional<User> find(String userId) {
        try {
            return Optional.of(calls.call(USER, () -> stub.getUser(GetUserRequest.newBuilder().setUserId(userId).build())));
        } catch (EcomException e) {
            if (e.code() == ErrorCode.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
