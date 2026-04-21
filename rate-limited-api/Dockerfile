# Multi-stage build — keeps final image lean (~200MB vs ~700MB)
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
# Download deps first (cacheable layer — won't re-download unless pom.xml changes)
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /app/target/rate-limited-api-1.0.0.jar app.jar

# Non-root user — production security practice
RUN useradd -r -u 1001 appuser
USER appuser

EXPOSE 8080

# JVM flags for container awareness (important for memory limits in K8s/Docker)
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
