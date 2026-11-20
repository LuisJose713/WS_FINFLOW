# ── Stage 1: Build ────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Disable vulnerable accelerated Base64 decoder on the Maven builder JDK.
ENV MAVEN_OPTS="-XX:-UseBASE64Intrinsics"

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# ── Stage 2: Runtime ──────────────────────────────────────
FROM amazoncorretto:21.0.13-alpine

# Fail closed if the image tag is ever resolved to an unpatched Java4Shell JRE.
RUN java -version 2>&1 | grep -F 'version "21.0.13.1"'

WORKDIR /app

RUN addgroup -S finflow && adduser -S finflow -G finflow
USER finflow

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]