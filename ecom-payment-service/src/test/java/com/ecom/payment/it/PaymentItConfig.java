package com.ecom.payment.it;

import com.ecom.platform.grpc.GrpcChannels;
import com.ecom.platform.grpc.GrpcProperties;
import io.grpc.Channel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class PaymentItConfig {

    @Bean(destroyMethod = "close")
    FakePeers fakePeers() {
        return new FakePeers();
    }

    /** Routes the "order" and "inventory" clients to the in-process fakes instead of Netty. */
    @Bean
    GrpcChannels grpcChannels(FakePeers peers) {
        return new GrpcChannels(new GrpcProperties()) {
            @Override
            public Channel channel(String name) {
                return switch (name) {
                    case "order", "inventory" -> peers.channel();
                    default -> throw new IllegalStateException("No fake for gRPC client " + name);
                };
            }
        };
    }
}
