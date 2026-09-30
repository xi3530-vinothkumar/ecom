package com.ecom.payment.it;

import com.ecom.platform.grpc.GrpcChannels;
import io.grpc.Channel;
import java.util.Set;
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
        return new GrpcChannels(null, Set.of()) {
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
