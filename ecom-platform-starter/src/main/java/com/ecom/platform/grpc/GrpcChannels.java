package com.ecom.platform.grpc;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.grpc.client.GrpcChannelFactory;

/**
 * One cached channel per peer, created by Spring gRPC's {@link GrpcChannelFactory} from
 * {@code spring.grpc.client.channel.<name>.*} (target, default deadline, keepalive). Global client
 * interceptors (context propagation, observation) are applied by the factory, which also shuts
 * the channels down. Usage: {@code CatalogServiceGrpc.newBlockingStub(channels.channel("catalog"))}.
 */
public class GrpcChannels {

    private final GrpcChannelFactory factory;
    private final Set<String> configured;
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    public GrpcChannels(GrpcChannelFactory factory, Set<String> configured) {
        this.factory = factory;
        this.configured = Set.copyOf(configured);
    }

    public Channel channel(String name) {
        if (!configured.contains(name)) {
            // without this, the factory would silently treat the name as a DNS target
            throw new IllegalStateException("Missing property spring.grpc.client.channel." + name + ".target");
        }
        return channels.computeIfAbsent(name, factory::createChannel);
    }
}
