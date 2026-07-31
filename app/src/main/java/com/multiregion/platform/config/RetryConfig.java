package com.multiregion.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Enables Spring Framework retry for HA/DR failover resilience.
 * <p>
 * During failover (writer node crash), the AWS JDBC Wrapper failover2 plugin
 * detects the broken connection and reconnects to the new writer. This takes
 * approximately 3-10 seconds. During this window, write operations fail with
 * connection errors.
 * <p>
 * {@code @Retryable} on service methods automatically retries transient
 * {@code DataAccessResourceFailureException} and
 * {@code TransientDataAccessResourceException} with exponential backoff,
 * covering the failover window without returning errors to the client.
 */
@Configuration
@EnableResilientMethods
public class RetryConfig {
    // Marker class — @EnableResilientMethods activates Spring's core retry for all
    // @Retryable beans in this context.
}
