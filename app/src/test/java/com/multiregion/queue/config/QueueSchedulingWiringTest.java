package com.multiregion.queue.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class QueueSchedulingWiringTest {

    @Test
    void queueReconciliationUsesItsDedicatedScheduler() throws NoSuchMethodException {
        Method localReconciliation = QueueCoordinationScheduler.class
                .getDeclaredMethod("reconcileLocalListeners");
        Method takeoverReconciliation = QueueCoordinationScheduler.class
                .getDeclaredMethod("reconcileTakeoverListeners");
        Method schedulerFactory = QueueListenerConfiguration.class
                .getDeclaredMethod("queueTaskScheduler");

        assertThat(localReconciliation.getAnnotation(Scheduled.class).scheduler())
                .isEqualTo("queueTaskScheduler");
        assertThat(takeoverReconciliation.getAnnotation(Scheduled.class).scheduler())
                .isEqualTo("queueTaskScheduler");
        assertThat(schedulerFactory.getAnnotation(Bean.class).name())
                .containsExactly("queueTaskScheduler");

        ThreadPoolTaskScheduler scheduler = new QueueListenerConfiguration().queueTaskScheduler();
        assertThat(scheduler.getPoolSize()).isEqualTo(2);
        assertThat(scheduler.getThreadNamePrefix()).isEqualTo("queue-coordination-");
    }
}
