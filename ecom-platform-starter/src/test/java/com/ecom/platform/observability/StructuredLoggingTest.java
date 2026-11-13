package com.ecom.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** logback-spring.xml + platform defaults: logstash JSON with service name, PII masked. */
@SpringBootTest(
        classes = StructuredLoggingTest.App.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {}

    @Test
    void logsLogstashJsonWithServiceAndMaskedPii(CapturedOutput output) {
        LoggerFactory.getLogger(StructuredLoggingTest.class)
                .info("welcome jane.doe@example.com, we will call +31 6 1234 5678");

        String line = output.getOut().lines()
                .filter(l -> l.contains("StructuredLoggingTest"))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(line).startsWith("{").contains("\"@timestamp\"", "\"logger_name\"", "\"service\":\"platform-test\"");
        assertThat(line).contains("welcome ***@***, we will call ***PHONE***")
                .doesNotContain("jane.doe@example.com", "1234 5678");
    }
}
