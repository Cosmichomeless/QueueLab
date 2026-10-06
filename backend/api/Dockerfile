# Imagen de la API de QueueLab (build multietapa).
#
# El contexto de build es backend/, no backend/api/ (la API depende del módulo core):
#   docker build -f backend/api/Dockerfile -t queuelab-api backend
#
# La configuración es externa: nada de credenciales en la imagen (ver docs/containers-backend.md).

# ---- Etapa 1: compilar y extraer capas -------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Primero solo los pom y el wrapper: Maven resuelve las dependencias con la caché montada
# (/root/.m2) y no vuelve a descargarlas mientras no cambie el pom o la caché.
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY core/pom.xml core/
COPY api/pom.xml api/
# El pom padre declara estos módulos: Maven necesita su pom aunque aquí no se compilen.
COPY worker/pom.xml worker/
COPY e2e/pom.xml e2e/

COPY core/src core/src
COPY api/src api/src

# El perfil `local` lleva las credenciales de desarrollo de docker-compose.yml: no debe viajar en la imagen.
RUN rm -f api/src/main/resources/application-local.yml

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl api -am package -DskipTests \
    && java -Djarmode=tools -jar api/target/queuelab-api-*.jar extract --layers --destination /build/layers \
    && mv /build/layers/application/queuelab-api-*.jar /build/layers/application/app.jar

# ---- Etapa 2: imagen de ejecución (solo JRE, sin Maven ni código fuente) ----------------------------------------
FROM eclipse-temurin:25-jre-alpine

# Usuario sin privilegios; el directorio de almacenamiento le pertenece para que un volumen nuevo herede el dueño.
RUN addgroup -S -g 10001 queuelab \
    && adduser -S -u 10001 -G queuelab -h /app queuelab \
    && mkdir -p /data/storage \
    && chown queuelab:queuelab /data/storage

WORKDIR /app
# De lo que menos cambia a lo que más: las dependencias quedan en capas que se reutilizan entre builds.
COPY --from=build --chown=queuelab:queuelab /build/layers/dependencies/ ./
COPY --from=build --chown=queuelab:queuelab /build/layers/snapshot-dependencies/ ./
COPY --from=build --chown=queuelab:queuelab /build/layers/application/ ./

ENV QUEUELAB_STORAGE_DIRECTORY=/data/storage \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=30s

# Los ficheros subidos y los resultados viven aquí; API y worker deben montar el MISMO volumen.
VOLUME /data/storage

USER queuelab
EXPOSE 8080

# /actuator/health incluye la base de datos. RabbitMQ y Redis no cuentan: la API acepta trabajos sin ellos.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1

# Forma exec: java es el PID 1 y recibe SIGTERM directamente (parada ordenada de Spring Boot).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
