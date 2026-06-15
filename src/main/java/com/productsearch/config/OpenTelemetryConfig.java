package com.productsearch.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class OpenTelemetryConfig {

    // LangSmith OTLP/HTTP traces endpoint — the "/otel/v1/traces" path is required
    private static final String OTLP_PATH = "/otel/v1/traces";

    @Value("${LANGSMITH_ENDPOINT:https://api.smith.langchain.com}")
    private String langsmithEndpoint;

    @Value("${LANGSMITH_API_KEY:}")
    private String langsmithApiKey;

    @Value("${LANGSMITH_PROJECT:product-search-rag}")
    private String langsmithProject;

    @Bean
    public OpenTelemetry openTelemetry() {
        if (langsmithApiKey == null || langsmithApiKey.isBlank()) {
            log.warn("LANGSMITH_API_KEY is not set — OTel traces will be discarded (using no-op SDK)");
            return OpenTelemetry.noop();
        }

        String otlpEndpoint = langsmithEndpoint + OTLP_PATH;
        log.info("Configuring OTel OTLP exporter → {} (project: {})", otlpEndpoint, langsmithProject);

        // ── OTLP/HTTP exporter ─────────────────────────────────────────────────
        // Vendor-neutrality point: swap endpoint + headers to use any OTel backend.
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .addHeader("x-api-key",        langsmithApiKey)
                .addHeader("langsmith-project", langsmithProject)
                .build();

        // ── Resource — identifies this service in the LangSmith UI ─────────────
        Resource resource = Resource.getDefault().merge(
                Resource.create(io.opentelemetry.api.common.Attributes.of(
                        io.opentelemetry.api.common.AttributeKey.stringKey("service.name"),    "product-search-rag",
                        io.opentelemetry.api.common.AttributeKey.stringKey("service.version"), "1.0.0"
                ))
        );

        // ── Tracer provider ────────────────────────────────────────────────────
        // scheduleDelay reduced from the default 5 s to 1 s so that spans arrive
        // in LangSmith before the async LLM judge finishes and tries to post feedback.
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(1, TimeUnit.SECONDS)
                        .build())
                .setResource(resource)
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        // Register as the global instance so any library instrumentation picks it up
        io.opentelemetry.api.GlobalOpenTelemetry.set(sdk);

        log.info("OpenTelemetry SDK initialised — traces will appear in LangSmith project '{}'", langsmithProject);
        return sdk;
    }

    @Bean
    public Tracer productTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("com.productsearch", "1.0.0");
    }
}
