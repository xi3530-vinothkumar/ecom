package com.ecom.platform.grpc;

import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;

/**
 * Named outbound channels from {@code ecom.grpc.clients.<name>.address}, with correlation/auth
 * propagation and default deadline applied. Usage:
 * {@code CatalogServiceGrpc.newBlockingStub(channels.channel("catalog"))}.
 */
public class GrpcChannels implements DisposableBean {

    private final GrpcProperties props;
    private final Map<String, ManagedChannel> managed = new ConcurrentHashMap<>();

    public GrpcChannels(GrpcProperties props) {
        this.props = props;
    }

    public Channel channel(String name) {
        GrpcProperties.Client client = props.getClients().get(name);
        if (client == null || client.getAddress() == null) {
            throw new IllegalStateException("Missing property ecom.grpc.clients." + name + ".address");
        }
        ManagedChannel ch = managed.computeIfAbsent(name, n -> NettyChannelBuilder.forTarget(client.getAddress())
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .build());
        return ClientInterceptors.intercept(ch, new CallContextClientInterceptor(client.getDeadline()));
    }

    @Override
    public void destroy() {
        managed.values().forEach(ManagedChannel::shutdown);
    }
}
