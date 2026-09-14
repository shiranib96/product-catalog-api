package nl.casus.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import nl.casus.catalog.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

class SearchPerformanceIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SearchPerformanceIT.class);

    private static final int CATALOG_SIZE = 100_000;
    private static final Duration LIMIT = Duration.ofSeconds(1);
    private static final List<String> QUERIES = List.of(
            "running shoe", "globex", "headph", "red trail jacket", "PERF-4242", "hedphones", "office chair black");

    @Autowired
    private ProductService products;

    @Test
    void searchesLargeCatalogWithinOneSecond() {
        createCatalog();

        for (String query : QUERIES) {
            products.search(query, 0, 20); // warm up
            long start = System.nanoTime();
            var result = products.search(query, 0, 20);
            var elapsed = Duration.ofNanos(System.nanoTime() - start);

            log.info("Search for '{}' found {} of {} products in {} ms",
                    query, result.totalItems(), CATALOG_SIZE, elapsed.toMillis());
            assertThat(result.totalItems()).as("matches for '%s'", query).isPositive();
            assertThat(elapsed).as("duration of search for '%s'", query).isLessThan(LIMIT);
        }
    }

    private void createCatalog() {
        jdbc.sql("""
                INSERT INTO product (sku, name, description, brand, category, attributes, created_at, updated_at)
                SELECT 'PERF-' || n,
                       (ARRAY['Trail', 'Road', 'City', 'Kids', 'Pro'])[n % 5 + 1] || ' ' ||
                       (ARRAY['running shoe', 'jacket', 'backpack', 'headphones', 'coffee maker', 'desk lamp',
                              'office chair'])[n % 7 + 1] || ' ' || n,
                       'Generated product ' || n || ' ' || md5(n::text),
                       (ARRAY['Acme', 'Globex', 'Initech', 'Umbrella', 'Hooli'])[n % 5 + 1],
                       (ARRAY['Sports', 'Outdoor', 'Electronics', 'Home', 'Office'])[n % 5 + 1],
                       jsonb_build_object('color', (ARRAY['red', 'blue', 'green', 'black', 'white', 'yellow'])[n % 6 + 1],
                                          'size', (n % 50)::text),
                       now(), now()
                  FROM generate_series(1, :count) AS n
                """)
                .param("count", CATALOG_SIZE)
                .update();
        jdbc.sql("ANALYZE product").update();
    }
}
