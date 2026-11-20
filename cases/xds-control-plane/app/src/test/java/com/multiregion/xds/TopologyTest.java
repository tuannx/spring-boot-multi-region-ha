package com.multiregion.xds;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TopologyTest {

    private final Topology usFirst = new Topology(List.of(
            new RegionState("us-east-1", "app-us", 8080, 0, 100, true),
            new RegionState("eu-west-1", "app-eu", 8081, 1, 100, true),
            new RegionState("ap-southeast-1", "app-apse1", 8082, 2, 100, true)));

    @Test
    void drainKeepsPrioritiesAndWeights() {
        Topology drained = usFirst.withHealthy("us-east-1", false);

        assertFalse(drained.region("us-east-1").healthy());
        assertEquals(0, drained.region("us-east-1").priority());
        assertEquals(1, drained.region("eu-west-1").priority());
    }

    @Test
    void weightsPutNamedRegionsInPriorityZeroAndKeepTheRestBehind() {
        Topology split = usFirst.withWeights(Map.of("us-east-1", 90, "eu-west-1", 10));

        assertEquals(0, split.region("us-east-1").priority());
        assertEquals(90, split.region("us-east-1").weight());
        assertEquals(0, split.region("eu-west-1").priority());
        assertEquals(10, split.region("eu-west-1").weight());
        assertEquals(1, split.region("ap-southeast-1").priority());
    }

    @Test
    void prioritiesMustHaveNoGaps() {
        assertThrows(IllegalArgumentException.class, () -> new Topology(List.of(
                new RegionState("us-east-1", "app-us", 8080, 0, 100, true),
                new RegionState("eu-west-1", "app-eu", 8081, 2, 100, true))));
    }

    @Test
    void unknownRegionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> usFirst.withHealthy("mars-1", false));
    }
}
