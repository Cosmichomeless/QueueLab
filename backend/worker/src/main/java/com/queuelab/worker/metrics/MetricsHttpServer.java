package com.queuelab.worker.metrics;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Sirve el registro de Prometheus en {@code GET /metrics}. El worker no tiene servidor web (es un proceso de
 * fondo), así que usa el servidor HTTP que trae el JDK en vez de arrastrar Tomcat.
 *
 * <p>Por defecto escucha solo en {@code 127.0.0.1}: las métricas no llevan autenticación. Para que un Prometheus
 * en otro contenedor lo alcance hay que abrirlo expresamente ({@code queuelab.metrics.address=0.0.0.0}) y
 * dejarlo en una red interna. Si el puerto está ocupado el worker sigue procesando trabajos sin métricas
 * (queda un error en el log): la observabilidad no debe tumbar el trabajo.
 */
@Component
@ConditionalOnProperty(name = "queuelab.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class MetricsHttpServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MetricsHttpServer.class);
    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final PrometheusMeterRegistry registry;
    private final String address;
    private final int configuredPort;

    private HttpServer server;
    private ExecutorService executor;

    public MetricsHttpServer(PrometheusMeterRegistry registry,
            @Value("${queuelab.metrics.address:127.0.0.1}") String address,
            @Value("${queuelab.metrics.port:8081}") int port) {
        this.registry = registry;
        this.address = address;
        this.configuredPort = port;
    }

    @Override
    public synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(address, configuredPort), 0);
            created.createContext("/metrics", this::handle);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            created.setExecutor(executor);
            created.start();
            server = created;
            log.info("Métricas de Prometheus en http://{}:{}/metrics", address, port());
        } catch (IOException e) {
            log.error("No se pudo abrir el endpoint de métricas en {}:{}; el worker sigue sin métricas",
                    address, configuredPort, e);
        }
    }

    @Override
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            executor.shutdown();
            server = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return server != null;
    }

    /** Puerto real (útil con {@code queuelab.metrics.port=0}); {@code -1} si no está abierto. */
    public synchronized int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
