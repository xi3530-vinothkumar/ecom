package com.ecom.platform.grpc;

import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.CorrelationIds;
import com.ecom.platform.core.EcomException;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binds {@link CallContext} from metadata around every listener callback (gRPC may run callbacks
 * on different threads) and translates {@link EcomException} into the matching gRPC status.
 */
public class CallContextServerInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CallContextServerInterceptor.class);

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(
            ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        String userId = headers.get(GrpcMetadataKeys.USER_ID);
        CallContext ctx = new CallContext(
                CorrelationIds.acceptOrGenerate(headers.get(GrpcMetadataKeys.CORRELATION_ID)),
                userId == null || userId.isBlank() ? null : userId,
                CallContext.parseRoles(headers.get(GrpcMetadataKeys.USER_ROLES)));

        ServerCall.Listener<Q> delegate = CallContext.call(ctx, () -> next.startCall(call, headers));
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(Q message) {
                run(ctx, call, () -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                run(ctx, call, super::onHalfClose);
            }

            @Override
            public void onCancel() {
                run(ctx, call, super::onCancel);
            }

            @Override
            public void onComplete() {
                run(ctx, call, super::onComplete);
            }

            @Override
            public void onReady() {
                run(ctx, call, super::onReady);
            }
        };
    }

    private static void run(CallContext ctx, ServerCall<?, ?> call, Runnable body) {
        CallContext.run(ctx, () -> {
            try {
                body.run();
            } catch (EcomException e) {
                closeQuietly(call, Status.fromCode(e.code().grpcCode()).withDescription(e.getMessage()));
            } catch (RuntimeException e) {
                log.error("Unhandled exception in gRPC call {}", call.getMethodDescriptor().getFullMethodName(), e);
                closeQuietly(call, Status.INTERNAL.withDescription("Internal error"));
            }
        });
    }

    private static void closeQuietly(ServerCall<?, ?> call, Status status) {
        try {
            call.close(status, new Metadata());
        } catch (IllegalStateException alreadyClosed) {
            // call already completed; nothing to do
        }
    }
}
