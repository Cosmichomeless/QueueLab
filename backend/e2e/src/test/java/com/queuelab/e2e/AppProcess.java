package com.queuelab.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/** Un jar ejecutable de Spring Boot lanzado como proceso aparte, con su salida en {@code target/e2e-logs}. */
final class AppProcess implements AutoCloseable {

    private final String name;
    private final Path log;
    private final Process process;

    private AppProcess(String name, Path log, Process process) {
        this.name = name;
        this.log = log;
        this.process = process;
    }

    /** Lanza el jar de {@code ../<module>/target}. Falla con un mensaje claro si aún no está empaquetado. */
    static AppProcess start(String module, Map<String, String> env) throws IOException {
        Path base = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
        Path jar = findJar(base.resolveSibling(module).resolve("target"), module);
        Path logDir = Files.createDirectories(base.resolve("target/e2e-logs"));
        Path log = logDir.resolve(module + ".log");

        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString());
        // API y worker comparten el almacenamiento, dentro de target/ para no ensuciar el árbol de trabajo.
        builder.environment().put("QUEUELAB_STORAGE_DIRECTORY", base.resolve("target/e2e-storage").toString());
        // Los e2e no levantan Redis: sin él el límite de envíos (que deja pasar si no lo alcanza) solo metería ruido.
        builder.environment().put("QUEUELAB_RATE_LIMIT_ENABLED", "false");
        builder.environment().putAll(env);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        return new AppProcess(module, log, builder.start());
    }

    private static Path findJar(Path target, String module) throws IOException {
        if (!Files.isDirectory(target)) {
            throw new IllegalStateException(missing(module, target));
        }
        try (Stream<Path> files = Files.list(target)) {
            return files.filter(f -> f.getFileName().toString().matches("queuelab-" + module + "-.*\\.jar"))
                    .filter(f -> !f.getFileName().toString().endsWith(".original"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(missing(module, target)));
        }
    }

    private static String missing(String module, Path target) {
        return "No hay jar de " + module + " en " + target + ": ejecuta `./mvnw verify` desde backend/ "
                + "(o `./mvnw -pl " + module + " -am package`) antes de lanzar las pruebas e2e";
    }

    boolean isAlive() {
        return process.isAlive();
    }

    /** Últimas líneas del log, para diagnosticar un fallo. */
    String tail(int lines) {
        try {
            var all = Files.readAllLines(log);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "(sin log: " + e.getMessage() + ")";
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
