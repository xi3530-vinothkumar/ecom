package com.ecom.platform.grpc;

import com.ecom.platform.core.CallContext;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Re-emits correlation id + user identity as metadata and applies a default deadline. */
public class CallContextClientInterceptor implements ClientInterceptor {

    private final Duration defaultDeadline;

    public CallContextClientInterceptor(Duration defaultDeadline) {
        this.defaultDeadline = defaultDeadline;
    }

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        CallOptions effective = options.getDeadline() == null
                ? options.withDeadlineAfter(defaultDeadline.toMillis(), TimeUnit.MILLISECONDS)
                : options;
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, effective)) {
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
