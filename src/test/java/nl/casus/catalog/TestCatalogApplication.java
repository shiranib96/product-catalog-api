package nl.casus.catalog;

import java.util.stream.Stream;
import nl.casus.catalog.support.TestInfrastructure;
import org.springframework.boot.SpringApplication;

// ./mvnw spring-boot:test-run
// runs the app with an embedded postgres. Start the mock source first: python3 mock-source/server.py
public class TestCatalogApplication {

    public static void main(String[] args) {
        Stream<String> database = TestInfrastructure.properties().entrySet().stream()
                .map(property -> "--%s=%s".formatted(property.getKey(), property.getValue()));
        Stream<String> dev = Stream.of("--catalog.source.sync-interval=PT1M");
        String[] allArgs = Stream.of(database, dev, Stream.of(args)).flatMap(s -> s).toArray(String[]::new);
        SpringApplication.from(CatalogApplication::main).run(allArgs);
    }
}
