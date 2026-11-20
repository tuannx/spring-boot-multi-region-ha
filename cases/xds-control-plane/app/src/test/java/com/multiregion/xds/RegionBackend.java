package com.multiregion.xds;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** A gRPC backend that answers every call with its region name; no protoc needed. */
final class RegionBackend implements AutoCloseable {

    static final MethodDescriptor<String, String> WHICH_REGION = MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("products.Region", "Which"))
            .setRequestMarshaller(new Utf8())
            .setResponseMarshaller(new Utf8())
            .build();

    private final Server server;

    RegionBackend(String region) throws IOException {
        ServerServiceDefinition service = ServerServiceDefinition.builder("products.Region")
                .addMethod(WHICH_REGION, ServerCalls.asyncUnaryCall((request, response) -> {
                    response.onNext(region);
                    response.onCompleted();
                }))
                .build();
        this.server = Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
                .addService(service).build().start();
    }

    int port() {
        return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private static final class Utf8 implements MethodDescriptor.Marshaller<String> {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
