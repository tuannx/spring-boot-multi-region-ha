package com.multiregion.platform.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Permissive CORS for the local demo dashboard.
 *
 * <p>The single-file dashboard ({@code /demo.html}) polls both regional apps
 * straight from the browser, which is cross-origin across localhost ports.
 * This configuration exists for local demonstration only; a real deployment
 * terminates the dashboard behind the same origin or an allow-listed domain.
 */
@Configuration
public class DemoCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/demo/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "OPTIONS")
                .maxAge(3600);
        registry.addMapping("/health")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "OPTIONS")
                .maxAge(3600);
        registry.addMapping("/admin/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .maxAge(3600);
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .maxAge(3600);
    }
}
