package com.multiregion.cassandra.platform.web;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.NodeState;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.multiregion.cassandra.platform.config.RegionProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthResource {

    private final CqlSession session;
    private final RegionProperties region;

    public HealthResource(CqlSession session, RegionProperties region) {
        this.session = session;
        this.region = region;
    }

    @GetMapping("/health")
    public ResponseEntity<RegionHealth> health() {
        try {
            session.execute(SimpleStatement.builder("SELECT release_version FROM system.local")
                    .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_ONE)
                    .build());

            Metadata metadata = session.getMetadata();
            Map<String, Integer> upByDatacenter = new LinkedHashMap<>();
            int localKnown = 0;
            int localUp = 0;
            for (Node node : metadata.getNodes().values()) {
                String datacenter = node.getDatacenter();
                if (datacenter == null) {
                    continue;
                }
                if (region.localDatacenter().equals(datacenter)) {
                    localKnown++;
                }
                if (node.getState() == NodeState.UP) {
                    upByDatacenter.merge(datacenter, 1, Integer::sum);
                    if (region.localDatacenter().equals(datacenter)) {
                        localUp++;
                    }
                }
            }

            RegionHealth body = new RegionHealth(
                    localUp >= 2 ? "UP" : "DEGRADED",
                    region.name(),
                    region.localDatacenter(),
                    localUp,
                    localKnown,
                    Map.copyOf(upByDatacenter));
            return localUp >= 2
                    ? ResponseEntity.ok(body)
                    : ResponseEntity.status(503).body(body);
        } catch (RuntimeException unavailable) {
            RegionHealth body = new RegionHealth(
                    "DOWN",
                    region.name(),
                    region.localDatacenter(),
                    0,
                    0,
                    Map.of());
            return ResponseEntity.status(503).body(body);
        }
    }
}
