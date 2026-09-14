package nl.casus.catalog.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

// A real Postgres without Docker, started once and shared by all tests
public final class TestInfrastructure {

    private static EmbeddedPostgres postgres;

    private TestInfrastructure() {
    }

    public static synchronized Map<String, String> properties() {
        if (postgres == null) {
            start();
        }
        return Map.of(
                "spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"),
                "spring.datasource.username", "postgres",
                "spring.datasource.password", "postgres");
    }

    private static void start() {
        try {
            postgres = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(TestInfrastructure::stop, "postgres-shutdown"));
    }

    private static void stop() {
        try {
            postgres.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
