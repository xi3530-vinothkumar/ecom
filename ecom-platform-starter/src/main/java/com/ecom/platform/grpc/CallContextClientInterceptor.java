package com.ecom.platform.grpc;

import com.ecom.platform.core.CallContext;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/**
 * Re-emits correlation id + user identity as metadata. Registered as a Spring gRPC global client
 * interceptor; the per-channel default deadline comes from
 * {@code spring.grpc.client.channel.<name>.default.deadline}.
 */
public class CallContextClientInterceptor implements ClientInterceptor {

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<R> listener, Metadata headers) {
                CallContext.current().ifPresent(ctx -> {
                    if (ctx.correlationId() != null) {
                        headers.put(GrpcMetadataKeys.CORRELATION_ID, ctx.correlationId());
                    }
                    if (ctx.userId() != null) {
                        headers.put(GrpcMetadataKeys.USER_ID, ctx.userId());
                    }
                    if (!ctx.roles().isEmpty()) {
                        headers.put(GrpcMetadataKeys.USER_ROLES, String.join(",", ctx.roles()));
                    }
                });
                super.start(listener, headers);
            }
        };
    }
}
