package com.productsearch.infra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class EnvFilePropertyLoader implements EnvironmentPostProcessor, Ordered {

    private static final String FILE_NAME       = "env.properties";
    private static final String PROPERTY_SOURCE = "envFileProperties";
    private static final int    MAX_PARENT_HOPS = 3;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        Path envFile = locate();
        if (envFile == null) return;

        Properties props = new Properties();
        try {
            props.load(Files.newBufferedReader(envFile));
        } catch (IOException e) {
            return;
        }

        environment.getPropertySources()
                .addFirst(new PropertiesPropertySource(PROPERTY_SOURCE, props));

        props.forEach((k, v) -> {
            String key = (String) k;
            if (System.getProperty(key) == null) {
                System.setProperty(key, (String) v);
            }
        });
    }

    private Path locate() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i <= MAX_PARENT_HOPS && dir != null; i++) {
            Path candidate = dir.resolve(FILE_NAME);
            if (Files.isReadable(candidate)) return candidate;
            dir = dir.getParent();
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
