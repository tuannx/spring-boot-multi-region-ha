# ============================================
# Stage 1: Build with Gradle + JDK 17
# ============================================
FROM gradle:8-jdk17 AS builder

WORKDIR /app

# Copy build configuration first for dependency caching
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle gradle/

# Download dependencies (cached layer)
RUN gradle dependencies --no-daemon || true

# Copy source code
COPY src src/

# Build the application
RUN gradle build --no-daemon -x test

# ============================================
# Stage 2: Runtime with JRE 17
# ============================================
FROM eclipse-temurin:17-jre

WORKDIR /app

# Create non-root user for security
RUN groupadd -r spring && useradd -r -g spring spring

# Copy the built JAR from builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Set ownership
RUN chown -R spring:spring /app

# Switch to non-root user
USER spring

# Expose application port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# Entry point with region-aware profile
ENTRYPOINT ["java", \
  "-jar", "app.jar", \
  "-Djava.security.egd=file:/dev/./urandom" \
]
