package nl.casus.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import nl.casus.catalog.support.IntegrationTest;
import nl.casus.catalog.update.ProductUpdate;
import nl.casus.catalog.update.UpdateService;
import nl.casus.catalog.update.UpdateService.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

class ProductSearchIT extends IntegrationTest {

    @Autowired
    private ProductService products;

    @Autowired
    private UpdateService updates;

    @BeforeEach
    void createCatalog() {
        add("SONY-WH1000XM5", "Sony WH-1000XM5", "Wireless noise cancelling headphones", "Sony", "Audio",
                Map.of("color", "Midnight Blue"));
        add("BOSE-QC45", "Bose QuietComfort 45", "Comfortable over-ear headphones", "Bose", "Audio",
                Map.of("color", "White Smoke"));
        add("LOGITECH-MX3S", "Logitech MX Master 3S Wireless Mouse", "Ergonomic mouse for productivity", "Logitech",
                "Computer accessories", Map.of("color", "Graphite"));
        add("NIKE-PEGASUS-41", "Nike Pegasus 41", "Responsive running shoes for daily training", "Nike", "Running shoes",
                Map.of("color", "Black", "size", "44"));
        add("MOCCAMASTER-KBG", "Moccamaster KBG Select", "Filter coffee maker, handmade in the Netherlands", "Technivorm",
                "Coffee", Map.of("color", "Matt Black", "capacity", "1.25 l"));
        add("PHILIPS-LATTEGO", "Philips 5400 LatteGo", "Fully automatic espresso machine with milk system", "Philips",
                "Coffee", Map.of());
    }

    @Test
    void findsProductsByName() {
        assertThat(search("pegasus")).containsExactly("NIKE-PEGASUS-41");
    }

    @Test
    void findsProductsBySku() {
        assertThat(search("BOSE-QC45")).containsExactly("BOSE-QC45");
    }

    @Test
    void findsProductsByBrand() {
        assertThat(search("technivorm")).containsExactly("MOCCAMASTER-KBG");
    }

    @Test
    void findsProductsByCategory() {
        assertThat(search("audio")).containsExactlyInAnyOrder("SONY-WH1000XM5", "BOSE-QC45");
    }

    @Test
    void findsProductsByAttributeValue() {
        assertThat(search("midnight")).containsExactly("SONY-WH1000XM5");
    }

    @Test
    void findsProductsByDescription() {
        assertThat(search("espresso")).containsExactly("PHILIPS-LATTEGO");
    }

    @Test
    void matchesPartialWords() {
        assertThat(search("headph")).containsExactlyInAnyOrder("SONY-WH1000XM5", "BOSE-QC45");
    }

    @Test
    void requiresAllWordsAcrossCharacteristics() {
        assertThat(search("black coffee")).containsExactly("MOCCAMASTER-KBG");
    }

    @Test
    void isCaseInsensitive() {
        assertThat(search("MOCCAMASTER")).containsExactly("MOCCAMASTER-KBG");
    }

    @Test
    void toleratesTypos() {
        assertThat(search("moccamastr")).containsExactly("MOCCAMASTER-KBG");
    }

    @Test
    void onlyMatchesFuzzilyWhenNothingMatchesExactly() {
        add("SONY-WH1000XM4", "Sony WH-1000XM4", "Previous generation headphones", "Sony", "Audio", Map.of());

        assertThat(search("WH-1000XM5")).containsExactly("SONY-WH1000XM5");
    }

    @Test
    void ranksNameMatchesAboveDescriptionMatches() {
        assertThat(search("wireless")).containsExactly("LOGITECH-MX3S", "SONY-WH1000XM5");
    }

    @Test
    void treatsSearchSyntaxAsPlainText() {
        assertThat(search("sony & | ! :*")).containsExactly("SONY-WH1000XM5");
    }

    @Test
    void findsNothingForInputWithoutWords() {
        assertThat(products.search("!!!", 0, 20).totalItems()).isZero();
    }

    @Test
    void paginatesSearchResults() {
        var firstPage = products.search("coffee", 0, 1);
        var secondPage = products.search("coffee", 1, 1);

        assertThat(firstPage.totalItems()).isEqualTo(2);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(List.of(firstPage.items().getFirst().sku(), secondPage.items().getFirst().sku()))
                .containsExactlyInAnyOrder("MOCCAMASTER-KBG", "PHILIPS-LATTEGO");
    }

    @Test
    void reportsTotalWhenPagingBeyondTheLastResult() {
        var page = products.search("coffee", 5, 1);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalItems()).isEqualTo(2);
    }

    @Test
    void searchesThroughTheApi() {
        assertThat(mvc.get().uri("/api/v1/products/search").param("q", "quietcomfort"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.items[*].sku").asArray().containsExactly("BOSE-QC45");
    }

    @Test
    void requiresASearchTerm() {
        assertThat(mvc.get().uri("/api/v1/products/search")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/v1/products/search").param("q", " ")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsTooLargePages() {
        assertThat(mvc.get().uri("/api/v1/products/search").param("q", "sony").param("size", "1000"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("size");
    }

    private List<String> search(String query) {
        return products.search(query, 0, 20).items().stream().map(ProductResponse::sku).toList();
    }

    private void add(String sku, String name, String description, String brand, String category,
                     Map<String, String> attributes) {
        updates.applyProduct(new ProductUpdate(sku, name, description, brand, category, attributes,
                Instant.parse("2026-09-01T10:00:00Z")), Channel.SYNC);
    }
}
