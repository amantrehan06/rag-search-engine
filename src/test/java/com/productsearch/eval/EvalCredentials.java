package com.productsearch.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

final class EvalCredentials {

    private static final String[] REQUIRED = { "OPENAI_API_KEY", "PINECONE_API_KEY" };
    private static final AtomicBoolean bootstrapped = new AtomicBoolean(false);

    private EvalCredentials() {}

    static boolean available() {
        bootstrapEnvFileOnce();
        for (String name : REQUIRED) {
            if (resolve(name) == null) {
                System.err.println("[evals] disabled: " + name + " not set. " +
                        "Create env.properties (see env.properties.example) or pass -D" + name + "=...");
                return false;
            }
        }
        return true;
    }

    private static String resolve(String name) {
        String v = System.getProperty(name);
        if (v == null || v.isBlank()) v = System.getenv(name);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static void bootstrapEnvFileOnce() {
        if (!bootstrapped.compareAndSet(false, true)) return;
        Path envFile = locate();
        if (envFile == null) return;
        Properties props = new Properties();
        try {
            props.load(Files.newBufferedReader(envFile));
        } catch (IOException e) {
            System.err.println("[evals] failed reading " + envFile + ": " + e.getMessage());
            return;
        }
        for (String key : props.stringPropertyNames()) {
            if (resolve(key) != null) continue; // CLI -D / env wins
            String value = props.getProperty(key);
            if (value != null && !value.isBlank()) System.setProperty(key, value);
        }
    }

    private static Path locate() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (int i = 0; i <= 3 && cwd != null; i++, cwd = cwd.getParent()) {
            Path candidate = cwd.resolve("env.properties");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }
}
