package com.ecom.platform.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** Starts a Netty gRPC server exposing every {@link BindableService} bean (plaintext h2c in-cluster). */
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final GrpcProperties.Server props;
    private final List<BindableService> services;
    private final HealthStatusManager health = new HealthStatusManager();
    private volatile Server server;

    public GrpcServerLifecycle(GrpcProperties.Server props, List<BindableService> services) {
        this.props = props;
        this.services = services;
    }

    @Override
    public void start() {
        var builder = NettyServerBuilder.forPort(props.getPort())
                .addService(health.getHealthService())
                .addService(ProtoReflectionServiceV1.newInstance());
        var interceptor = new CallContextServerInterceptor();
        services.forEach(s -> builder.addService(ServerInterceptors.intercept(s, interceptor)));
        try {
            server = builder.build().start();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start gRPC server on port " + props.getPort(), e);
        }
        health.setStatus("", ServingStatus.SERVING);
        log.info("gRPC server started on port {} with {} service(s)", server.getPort(), services.size());
    }

    @Override
    public void stop() {
        if (server == null) {
            return;
        }
        health.enterTerminalState();
        server.shutdown();
        try {
            if (!server.awaitTermination(props.getShutdownGrace().toMillis(), TimeUnit.MILLISECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        }
        server = null;
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    /** Actual bound port (useful with port 0 in tests). */
    public int getPort() {
        return server == null ? -1 : server.getPort();
    }

    @Override
    public int getPhase() {
        // stop after the web server has stopped accepting requests
        return Integer.MAX_VALUE - 1000;
    }
}
