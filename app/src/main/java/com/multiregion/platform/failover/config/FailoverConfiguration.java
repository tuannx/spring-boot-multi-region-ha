package com.multiregion.platform.failover.config;

import com.multiregion.platform.config.MultiRegionConfig;
import com.multiregion.platform.failover.application.FailoverOrchestrator;
import com.multiregion.platform.failover.port.AuroraTopologyGateway;
import com.multiregion.platform.failover.port.FailoverPromotionGateway;
import com.multiregion.platform.failover.port.WriterTrafficSwitcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class FailoverConfiguration {

    @Bean
    public FailoverOrchestrator failoverOrchestrator(
            AuroraTopologyGateway topologyGateway,
            FailoverPromotionGateway promotionGateway,
            WriterTrafficSwitcher trafficSwitcher,
            MultiRegionConfig multiRegionConfig,
            @Value("${FAILOVER_FAILURE_THRESHOLD:3}") int failureThreshold,
            @Value("${FAILOVER_ALLOW_UNFENCED_PROMOTION:false}")
            boolean allowUnfencedPromotion) {
        return new FailoverOrchestrator(
                topologyGateway,
                promotionGateway,
                trafficSwitcher,
                multiRegionConfig.isPrimary(),
                failureThreshold,
                allowUnfencedPromotion);
    }

    @Bean(name = "failoverTaskScheduler")
    public ThreadPoolTaskScheduler failoverTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("failover-monitor-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
