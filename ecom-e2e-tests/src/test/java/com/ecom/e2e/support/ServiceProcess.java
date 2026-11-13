package com.ecom.e2e.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** One real service, launched as {@code java -jar <fat jar>} with its stdout/stderr captured to a log file. */
public final class ServiceProcess {

    private final String name;
    private final Path jar;
    private final Path logFile;
    private final int httpPort;
    private final int grpcPort;
    private final Map<String, String> env;
    private Process process;

    ServiceProcess(String name, Path jar, Path logFile, int httpPort, int grpcPort, Map<String, String> env) {
        this.name = name;
        this.jar = jar;
        this.logFile = logFile;
        this.httpPort = httpPort;
        this.grpcPort = grpcPort;
        this.env = env;
    }

    public String name() {
        return name;
    }

    public int httpPort() {
        return httpPort;
    }

    public int grpcPort() {
        return grpcPort;
    }

    public Path logFile() {
        return logFile;
    }

    void start() throws IOException {
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = new ArrayList<>(List.of(
                java, "-Xms64m", "-Xmx384m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
                "-Djava.net.preferIPv4Stack=true", "-jar", jar.toString()));
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
        pb.environment().putAll(env);
        process = pb.start();
    }

    /** Polls {@code /actuator/health/readiness} until 200, failing fast if the process dies. */
    void awaitReady(HttpClient http, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        URI uri = URI.create("http://localhost:" + httpPort + "/actuator/health/readiness");
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited with code " + process.exitValue()
                        + " during startup. Log tail (" + logFile + "):\n" + tail(60));
            }
            try {
                var resp = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return;
                }
            } catch (IOException _) {
                // not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            sleep(500);
        }
        throw new IllegalStateException(name + " not ready within " + timeout + ". Log tail (" + logFile + "):\n"
                + tail(60));
    }

    void stop() {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(25, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException _) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    public String tail(int lines) {
        try {
            List<String> all = Files.readAllLines(logFile);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "<log unavailable: " + e.getMessage() + ">";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
