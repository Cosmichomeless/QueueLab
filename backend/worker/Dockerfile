# Imagen del worker de QueueLab (build multietapa).
#
# El contexto de build es backend/, no backend/worker/ (el worker depende del módulo core):
#   docker build -f backend/worker/Dockerfile -t queuelab-worker backend
#
# Es una imagen independiente de la API: se puede escalar el número de réplicas sin tocar la API
# (ver docs/containers-backend.md). La configuración es externa: nada de credenciales en la imagen.

# ---- Etapa 1: compilar y extraer capas -------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Primero solo los pom y el wrapper: Maven resuelve las dependencias con la caché montada
# (/root/.m2) y no vuelve a descargarlas mientras no cambie el pom o la caché.
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY core/pom.xml core/
COPY worker/pom.xml worker/
# El pom padre declara estos módulos: Maven necesita su pom aunque aquí no se compilen.
COPY api/pom.xml api/
COPY e2e/pom.xml e2e/

COPY core/src core/src
COPY worker/src worker/src

# El perfil `local` lleva las credenciales de desarrollo de docker-compose.yml: no debe viajar en la imagen.
RUN rm -f worker/src/main/resources/application-local.yml

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl worker -am package -DskipTests \
    && java -Djarmode=tools -jar worker/target/queuelab-worker-*.jar extract --layers --destination /build/layers \
    && mv /build/layers/application/queuelab-worker-*.jar /build/layers/application/app.jar

# ---- Etapa 2: imagen de ejecución (solo JRE, sin Maven ni código fuente) ----------------------------------------
FROM eclipse-temurin:25-jre-alpine

# Mismo uid/gid que la imagen de la API: ambos procesos comparten el volumen de almacenamiento
# y deben poder leer y escribir los ficheros del otro.
RUN addgroup -S -g 10001 queuelab \
    && adduser -S -u 10001 -G queuelab -h /app queuelab \
    && mkdir -p /data/storage \
    && chown queuelab:queuelab /data/storage

WORKDIR /app
# De lo que menos cambia a lo que más: las dependencias quedan en capas que se reutilizan entre builds.
COPY --from=build --chown=queuelab:queuelab /build/layers/dependencies/ ./
COPY --from=build --chown=queuelab:queuelab /build/layers/snapshot-dependencies/ ./
COPY --from=build --chown=queuelab:queuelab /build/layers/application/ ./

# Al recibir SIGTERM el worker deja de tomar mensajes y espera a los trabajos en curso (5 s, el shutdownTimeout de
# Spring AMQP); lo que no termine en ese plazo lo recupera otra réplica al vencer el lease. El timeout de fase de
# abajo es solo el máximo que Spring concede a esa parada (ver docs/containers-backend.md).
ENV QUEUELAB_STORAGE_DIRECTORY=/data/storage \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=30s

# Los resultados se escriben aquí; API y worker deben montar el MISMO volumen.
VOLUME /data/storage

USER queuelab

# Sin HEALTHCHECK: el worker no abre puerto HTTP (web-application-type: none, sin actuator).
# Forma exec: java es el PID 1 y recibe SIGTERM directamente (parada ordenada).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
