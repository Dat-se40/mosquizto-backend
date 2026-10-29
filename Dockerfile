# ---- Build stage ----
# CRITICAL: This MUST be a JDK image so Maven has a compiler!
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B --no-transfer-progress

COPY src/ src/
RUN ./mvnw package -DskipTests -B --no-transfer-progress

RUN java -Djarmode=layertools -jar target/*.jar extract --destination extracted


# ---- Run stage ----
# RECOMMENDED: Use the Ubuntu-based JRE image here to support ONNX native C++ bindings
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN addgroup --system --gid 10001 appgroup && adduser --system --uid 10001 --ingroup appgroup appuser

# Copy layers in order of least-to-most frequently changed
COPY --from=builder /app/extracted/dependencies/ ./
COPY --from=builder /app/extracted/spring-boot-loader/ ./
COPY --from=builder /app/extracted/snapshot-dependencies/ ./
COPY --from=builder /app/extracted/application/ ./

# PVC preparation and optional bundled models use the same revision/checksum contract.
ARG INCLUDE_EMBEDDING_MODEL=false
ENV DJL_CACHE_DIR=/tmp/djl
RUN mkdir -p /models \
    && if [ "$INCLUDE_EMBEDDING_MODEL" = "true" ]; then \
         java org.springframework.boot.loader.launch.JarLauncher --prepare-model; \
       fi

RUN chown -R appuser:appgroup /app /models
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
