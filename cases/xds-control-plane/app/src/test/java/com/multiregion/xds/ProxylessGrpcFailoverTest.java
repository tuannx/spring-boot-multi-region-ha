package com.multiregion.xds;

import io.grpc.CallOptions;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.stub.ClientCalls;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three independent gRPC clients resolve {@code xds:///products} from one control plane. Each phase
 * changes one thing in the control plane and checks that all three clients follow, without a
 * restart, a redeploy or a line of routing code in the client.
 *
 * <p>The gRPC xDS client reads its bootstrap once per JVM, so every phase lives in this one class.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProxylessGrpcFailoverTest {

    private static final int CLIENTS = 3;
    private static final int CALLS = 300;
    private static final Duration CONVERGE = Duration.ofSeconds(10);
    private static final Duration BATCH_LIMIT = Duration.ofSeconds(3);

    private static RegionBackend us;
    private static RegionBackend eu;
    private static XdsControlPlane controlPlane;
    private static Topology initial;
    private static final List<ManagedChannel> channels = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        us = new RegionBackend("us-east-1");
        eu = new RegionBackend("eu-west-1");
        initial = new Topology(List.of(
                new RegionState("us-east-1", "127.0.0.1", us.port(), 0, 100, true),
                new RegionState("eu-west-1", "127.0.0.1", eu.port(), 1, 100, true)));
        controlPlane = new XdsControlPlane(0, initial).start();
        System.setProperty("io.grpc.xds.bootstrapConfig", """
                {
                  "xds_servers": [{
                    "server_uri": "127.0.0.1:%d",
                    "channel_creds": [{"type": "insecure"}],
                    "server_features": ["xds_v3"]
                  }],
                  "node": {"id": "grpc-client", "locality": {"region": "us-east-1"}}
                }
                """.formatted(controlPlane.port()));
        for (int i = 0; i < CLIENTS; i++) {
            channels.add(Grpc.newChannelBuilder("xds:///" + GrpcResources.SERVICE, InsecureChannelCredentials.create()).build());
        }
    }

    @AfterAll
    static void stop() throws Exception {
        for (ManagedChannel channel : channels) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        controlPlane.close();
        us.close();
        eu.close();
    }

    @Test
    @Order(1)
    void allClientsSendEverythingToPriorityZero() throws Exception {
        for (ManagedChannel channel : channels) {
            Map<String, Integer> counts = awaitCounts(channel, c -> c.getOrDefault("us-east-1", 0) == CALLS);
            assertEquals(Map.of("us-east-1", CALLS), counts);
        }
    }

    @Test
    @Order(2)
    void drainingUsMovesEveryClientToEuWithoutRestart() throws Exception {
        controlPlane.publish(controlPlane.topology().withHealthy("us-east-1", false));

        for (ManagedChannel channel : channels) {
            Map<String, Integer> counts = awaitCounts(channel, c -> c.getOrDefault("eu-west-1", 0) == CALLS);
            assertEquals(Map.of("eu-west-1", CALLS), counts);
        }
    }

    @Test
    @Order(3)
    void restoringUsBringsTrafficBack() throws Exception {
        controlPlane.publish(controlPlane.topology().withHealthy("us-east-1", true));

        for (ManagedChannel channel : channels) {
            Map<String, Integer> counts = awaitCounts(channel, c -> c.getOrDefault("us-east-1", 0) == CALLS);
            assertEquals(Map.of("us-east-1", CALLS), counts);
        }
    }

    @Test
    @Order(4)
    void weightsSplitTrafficNinetyTen() throws Exception {
        controlPlane.publish(controlPlane.topology().withWeights(Map.of("us-east-1", 90, "eu-west-1", 10)));

        for (ManagedChannel channel : channels) {
            Map<String, Integer> counts = awaitCounts(channel, c -> c.getOrDefault("eu-west-1", 0) > 0);
            double usShare = counts.getOrDefault("us-east-1", 0) / (double) CALLS;
            assertTrue(usShare > 0.80 && usShare < 0.97, "US share " + usShare + " from " + counts);
        }
    }

    /** Calls until the counts of one batch satisfy the condition, or CONVERGE passes. */
    private static Map<String, Integer> awaitCounts(ManagedChannel channel, Predicate<Map<String, Integer>> done)
            throws InterruptedException {
        long deadline = System.nanoTime() + CONVERGE.toNanos();
        Map<String, Integer> counts = Map.of();
        while (System.nanoTime() < deadline) {
            counts = batch(channel);
            if (done.test(counts)) {
                return counts;
            }
            System.out.println("Not converged yet: " + counts);
            Thread.sleep(50);
        }
        return counts;
    }

    /** One region name per successful call; a failed call is counted under its gRPC status. */
    private static Map<String, Integer> batch(ManagedChannel channel) throws InterruptedException {
        Map<String, Integer> counts = new TreeMap<>();
        long stopAt = System.nanoTime() + BATCH_LIMIT.toNanos();
        for (int i = 0; i < CALLS && System.nanoTime() < stopAt; i++) {
            String outcome;
            Future<String> call = ClientCalls.futureUnaryCall(channel.newCall(RegionBackend.WHICH_REGION,
                    CallOptions.DEFAULT.withDeadlineAfter(500, TimeUnit.MILLISECONDS)), "which");
            try {
                outcome = call.get(2, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                outcome = Status.fromThrowable(e.getCause()).getCode().name();
            } catch (TimeoutException e) {
                call.cancel(true);
                outcome = "NO_ANSWER";
            }
            counts.merge(outcome, 1, Integer::sum);
        }
        return counts;
    }
}
