package com.multiregion.elasticache.platform.web;

import com.multiregion.elasticache.platform.config.RegionProperties;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Properties;

@RestController
public class HealthResource {

    private final StringRedisTemplate redis;
    private final RegionProperties region;

    public HealthResource(StringRedisTemplate redis, RegionProperties region) {
        this.redis = redis;
        this.region = region;
    }

    @GetMapping("/health")
    public ResponseEntity<RegionHealth> health() {
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            Properties info = redis.execute(
                    (RedisCallback<Properties>) connection -> connection.serverCommands().info("replication"));
            String role = info == null ? "unknown" : info.getProperty("role", "unknown");
            boolean connected = "PONG".equals(pong);
            RegionHealth body = new RegionHealth(connected ? "UP" : "DOWN", region.name(), connected, role);
            return connected ? ResponseEntity.ok(body) : ResponseEntity.status(503).body(body);
        } catch (RuntimeException unavailable) {
            return ResponseEntity.status(503)
                    .body(new RegionHealth("DOWN", region.name(), false, "unknown"));
        }
    }
}
