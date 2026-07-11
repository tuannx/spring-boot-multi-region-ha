package com.multiregion.platform.failover.config;

import com.multiregion.platform.failover.scheduling.FailoverListener;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class FailoverSchedulingWiringTest {

    @Test
    void failoverMonitorUsesItsDedicatedSingleThreadScheduler() throws NoSuchMethodException {
        Method scheduledMethod = FailoverListener.class.getDeclaredMethod("checkPrimaryHealth");
        Method readyMethod = FailoverListener.class.getDeclaredMethod("onApplicationReady");
        Scheduled scheduled = scheduledMethod.getAnnotation(Scheduled.class);
        Method schedulerFactory = FailoverConfiguration.class
                .getDeclaredMethod("failoverTaskScheduler");
        Bean schedulerBean = schedulerFactory.getAnnotation(Bean.class);

        assertThat(scheduled).isNotNull();
        assertThat(readyMethod.getAnnotation(Order.class).value())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(scheduled.scheduler()).isEqualTo("failoverTaskScheduler");
        assertThat(schedulerBean.name()).containsExactly("failoverTaskScheduler");

        ThreadPoolTaskScheduler scheduler = new FailoverConfiguration().failoverTaskScheduler();
        assertThat(scheduler.getPoolSize()).isEqualTo(1);
        assertThat(scheduler.getThreadNamePrefix()).isEqualTo("failover-monitor-");
    }
}
