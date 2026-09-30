package com.ecom.platform.grpc;

import io.grpc.Metadata;

final class GrpcMetadataKeys {

    static final Metadata.Key<String> CORRELATION_ID =
            Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> USER_ID = Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> USER_ROLES = Metadata.Key.of("x-user-roles", Metadata.ASCII_STRING_MARSHALLER);

    private GrpcMetadataKeys() {}
}
