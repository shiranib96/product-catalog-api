package nl.casus.catalog.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import nl.casus.catalog.product.ProductRepository;
import nl.casus.catalog.source.SourceSync.SyncResult;
import nl.casus.catalog.support.IntegrationTest;
import nl.casus.catalog.support.SourceStub;
import nl.casus.catalog.update.PriceUpdate;
import nl.casus.catalog.update.ProductUpdate;
import nl.casus.catalog.update.UpdateService;
import nl.casus.catalog.update.UpdateService.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClientException;

class SourceSyncIT extends IntegrationTest {

    private static final String DAY_1 = "2026-09-01T10:00:00Z";
    private static final String DAY_2 = "2026-09-02T10:00:00Z";
    private static final String DAY_3 = "2026-09-03T10:00:00Z";

    @Autowired
    private SourceSync sourceSync;

    @Autowired
    private ProductRepository products;

    @Autowired
    private UpdateService updates;

    @Test
    void importsSourceProductsIntoOurModel() {
        SourceStub.setProducts(product("AUD-1", "Sony WH-1000XM5", DAY_1, "379.00", DAY_2));

        sourceSync.synchronize();

        var product = products.findBySku("AUD-1").orElseThrow();
        assertThat(product.getName()).isEqualTo("Sony WH-1000XM5");
        assertThat(product.getDescription()).isEqualTo("Wireless noise cancelling headphones");
        assertThat(product.getBrand()).isEqualTo("Sony");
        assertThat(product.getCategory()).isEqualTo("Electronics > Audio > Headphones");
        assertThat(product.getAttributes()).isEqualTo(Map.of("color", "Black", "connectivity", "Bluetooth 5.2"));
        assertThat(product.getSourceUpdatedAt()).isEqualTo(Instant.parse(DAY_1));
        assertThat(product.getPriceAmount()).isEqualByComparingTo("379.00");
        assertThat(product.getPriceCurrency()).isEqualTo("EUR");
        assertThat(product.getPriceUpdatedAt()).isEqualTo(Instant.parse(DAY_2));
    }

    @Test
    void readsAllPages() {
        // page size is 2 in the tests
        SourceStub.setProducts(product("P-1"), product("P-2"), product("P-3"), product("P-4"), product("P-5"));

        assertThat(sourceSync.synchronize()).isEqualTo(new SyncResult(5, 0, 0));
        assertThat(products.count()).isEqualTo(5);
    }

    @Test
    void appliesProductChangesFromTheSource() {
        SourceStub.setProducts(product("AUD-1", "Old name", DAY_1, "379.00", DAY_1));
        sourceSync.synchronize();

        SourceStub.setProducts(product("AUD-1", "New name", DAY_2, "379.00", DAY_1));

        assertThat(sourceSync.synchronize()).isEqualTo(new SyncResult(1, 0, 0));
        assertThat(products.findBySku("AUD-1").orElseThrow().getName()).isEqualTo("New name");
    }

    @Test
    void leavesProductsThatDidNotChangeAlone() {
        SourceStub.setProducts(product("AUD-1", "Sony", DAY_1, "379.00", DAY_1));
        sourceSync.synchronize();

        assertThat(sourceSync.synchronize()).isEqualTo(new SyncResult(0, 1, 0));
    }

    @Test
    void keepsNewerProductDataThatCameInThroughTheWebhook() {
        SourceStub.setProducts(product("AUD-1", "Sony", DAY_1, "379.00", DAY_1));
        sourceSync.synchronize();
        updates.applyProduct(new ProductUpdate("AUD-1", "Pushed name", null, "Sony", "Audio", Map.of(),
                Instant.parse(DAY_3)), Channel.WEBHOOK);

        // the pull still returns an older version of the product
        SourceStub.setProducts(product("AUD-1", "Pulled name", DAY_2, "379.00", DAY_1));
        sourceSync.synchronize();

        assertThat(products.findBySku("AUD-1").orElseThrow().getName()).isEqualTo("Pushed name");
    }

    @Test
    void correctsPricesWhenAWebhookCallWasMissed() {
        SourceStub.setProducts(product("AUD-1", "Sony", DAY_1, "379.00", DAY_1));
        sourceSync.synchronize();

        SourceStub.setProducts(product("AUD-1", "Sony", DAY_1, "359.00", DAY_3));
        sourceSync.synchronize();

        assertThat(price("AUD-1")).isEqualByComparingTo("359.00");
    }

    @Test
    void keepsANewerPriceThatCameInThroughTheWebhook() {
        SourceStub.setProducts(product("AUD-1", "Sony", DAY_1, "379.00", DAY_1));
        sourceSync.synchronize();
        updates.applyPrice(new PriceUpdate("AUD-1", new BigDecimal("349.00"), "EUR", Instant.parse(DAY_3)),
                Channel.WEBHOOK);

        SourceStub.setProducts(product("AUD-1", "Sony", DAY_1, "369.00", DAY_2));
        sourceSync.synchronize();

        assertThat(price("AUD-1")).isEqualByComparingTo("349.00");
    }

    @Test
    void importsProductsWithoutAPrice() {
        SourceStub.setProducts("""
                {"articleNumber": "NEW-1", "title": "Not priced yet", "lastModified": "2026-09-01T10:00:00Z"}""");

        sourceSync.synchronize();

        assertThat(price("NEW-1")).isNull();
    }

    @Test
    void skipsInvalidProducts() {
        SourceStub.setProducts(product("OK-1"), """
                {"articleNumber": "BAD-1", "title": "", "lastModified": "2026-09-01T10:00:00Z"}""", """
                {"articleNumber": "BAD 2", "title": "Spaces in article number", "lastModified": "2026-09-01T10:00:00Z"}""", """
                {"articleNumber": "BAD-3", "title": "No lastModified"}""");

        assertThat(sourceSync.synchronize()).isEqualTo(new SyncResult(1, 0, 3));
        assertThat(products.count()).isEqualTo(1);
    }

    @Test
    void registersOurWebhookWithTheSource() {
        sourceSync.synchronize();

        assertThat(SourceStub.webhookRegistrations()).singleElement().satisfies(registration -> assertThat(registration)
                .contains("\"url\":\"http://localhost:8080/api/v1/product-updates\"")
                .contains("\"secret\":\"" + WEBHOOK_SECRET + "\""));
    }

    @Test
    void stillImportsProductsWhenWebhookRegistrationFails() {
        SourceStub.failWebhookRegistrationsWith(400);
        SourceStub.setProducts(product("AUD-1"));

        assertThat(sourceSync.synchronize()).isEqualTo(new SyncResult(1, 0, 0));
    }

    @Test
    void failsWhenTheSourceIsDown() {
        SourceStub.failProductRequestsWith(503);

        assertThatThrownBy(() -> sourceSync.synchronize()).isInstanceOf(RestClientException.class);
        assertThat(products.count()).isZero();
    }

    private BigDecimal price(String sku) {
        return products.findBySku(sku).orElseThrow().getPriceAmount();
    }

    private static String product(String articleNumber) {
        return product(articleNumber, "Product " + articleNumber, DAY_1, "10.00", DAY_1);
    }

    private static String product(String articleNumber, String title, String lastModified, String amount,
                                  String priceValidFrom) {
        return """
                {
                  "articleNumber": "%s",
                  "title": "%s",
                  "details": "Wireless noise cancelling headphones",
                  "manufacturer": "Sony",
                  "categoryPath": "Electronics > Audio > Headphones",
                  "specs": [{"name": "color", "value": "Black"}, {"name": "connectivity", "value": "Bluetooth 5.2"}],
                  "lastModified": "%s",
                  "price": {"amount": "%s", "currency": "EUR"},
                  "priceValidFrom": "%s"
                }""".formatted(articleNumber, title, lastModified, amount, priceValidFrom);
    }
}
