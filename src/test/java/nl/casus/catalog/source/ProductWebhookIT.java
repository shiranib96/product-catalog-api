package nl.casus.catalog.source;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import nl.casus.catalog.product.ProductRepository;
import nl.casus.catalog.support.IntegrationTest;
import nl.casus.catalog.update.ProductUpdate;
import nl.casus.catalog.update.UpdateService;
import nl.casus.catalog.update.UpdateService.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class ProductWebhookIT extends IntegrationTest {

    @Autowired
    private UpdateService updates;

    @Autowired
    private ProductRepository products;

    @BeforeEach
    void createProduct() {
        updates.applyProduct(new ProductUpdate("AUD-1", "Sony WH-1000XM5", null, "Sony", "Audio", Map.of(),
                Instant.parse("2026-09-01T10:00:00Z")), Channel.SYNC);
    }

    @Test
    void createsProductsSentByTheSource() {
        String body = event(List.of(pricedProduct("NEW-1", "Fairphone 6", "2026-09-14T10:00:00Z", "649.00")), List.of());

        assertThat(post(body, sign(body, WEBHOOK_SECRET)))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"products": {"applied": 1, "stale": 0}, "prices": {"applied": 1, "stale": 0}, "unknownProducts": []}
                        """);
        var product = products.findBySku("NEW-1").orElseThrow();
        assertThat(product.getName()).isEqualTo("Fairphone 6");
        assertThat(product.getBrand()).isEqualTo("Fairphone");
        assertThat(product.getAttributes()).isEqualTo(Map.of("color", "Moss Green"));
        assertThat(product.getPriceAmount()).isEqualByComparingTo("649.00");
    }

    @Test
    void appliesNewerProductDataAndIgnoresOlder() {
        String newer = event(List.of(product("AUD-1", "Sony WH-1000XM5 (2026)", "2026-09-14T12:00:00Z")), List.of());
        String older = event(List.of(product("AUD-1", "Sony old name", "2026-09-14T11:00:00Z")), List.of());

        assertThat(post(newer, sign(newer, WEBHOOK_SECRET)))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"products": {"applied": 1, "stale": 0}}
                        """);
        assertThat(post(older, sign(older, WEBHOOK_SECRET)))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"products": {"applied": 0, "stale": 1}}
                        """);
        assertThat(products.findBySku("AUD-1").orElseThrow().getName()).isEqualTo("Sony WH-1000XM5 (2026)");
    }

    @Test
    void acceptsANewProductAndItsPriceChangeInOneCall() {
        String body = event(
                List.of(product("NEW-2", "Kobo Libra Colour", "2026-09-14T10:00:00Z")),
                List.of(priceChange("NEW-2", "229.99", "2026-09-14T10:00:00Z")));

        assertThat(post(body, sign(body, WEBHOOK_SECRET)))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"products": {"applied": 1, "stale": 0}, "prices": {"applied": 1, "stale": 0}, "unknownProducts": []}
                        """);
        assertThat(price("NEW-2")).isEqualByComparingTo("229.99");
    }

    @Test
    void appliesPriceChanges() {
        String body = event(List.of(), List.of(priceChange("AUD-1", "349.99", "2026-09-14T10:00:00Z")));

        assertThat(post(body, sign(body, WEBHOOK_SECRET)))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"products": {"applied": 0, "stale": 0}, "prices": {"applied": 1, "stale": 0}, "unknownProducts": []}
                        """);
        assertThat(price("AUD-1")).isEqualByComparingTo("349.99");
    }

    @Test
    void ignoresOlderAndRepeatedPriceChanges() {
        String newer = event(List.of(), List.of(priceChange("AUD-1", "300.00", "2026-09-14T12:00:00Z")));
        String older = event(List.of(), List.of(priceChange("AUD-1", "250.00", "2026-09-14T11:00:00Z")));

        post(newer, sign(newer, WEBHOOK_SECRET));

        assertThat(post(older, sign(older, WEBHOOK_SECRET)))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"prices": {"applied": 0, "stale": 1}}
                        """);
        assertThat(post(newer, sign(newer, WEBHOOK_SECRET)))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"prices": {"applied": 0, "stale": 1}}
                        """);
        assertThat(price("AUD-1")).isEqualByComparingTo("300.00");
    }

    @Test
    void reportsPricesForUnknownProducts() {
        String body = event(List.of(), List.of(
                priceChange("AUD-1", "349.99", "2026-09-14T10:00:00Z"),
                priceChange("UNKNOWN-1", "10.00", "2026-09-14T10:00:00Z")));

        assertThat(post(body, sign(body, WEBHOOK_SECRET)))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"prices": {"applied": 1, "stale": 0}, "unknownProducts": ["UNKNOWN-1"]}
                        """);
    }

    @Test
    void rejectsCallsWithoutSignature() {
        String body = event(List.of(product("AUD-1", "Hacked", "2026-09-14T10:00:00Z")), List.of());

        assertThat(post(body, null))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.title").isEqualTo("Invalid signature");
        assertThat(products.findBySku("AUD-1").orElseThrow().getName()).isEqualTo("Sony WH-1000XM5");
    }

    @Test
    void rejectsCallsSignedWithAnotherSecret() {
        String body = event(List.of(), List.of(priceChange("AUD-1", "1.00", "2026-09-14T10:00:00Z")));

        assertThat(post(body, sign(body, "not-the-secret"))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(price("AUD-1")).isNull();
    }

    @Test
    void rejectsBodiesChangedAfterSigning() {
        String body = event(List.of(), List.of(priceChange("AUD-1", "349.99", "2026-09-14T10:00:00Z")));
        String signature = sign(body, WEBHOOK_SECRET);

        assertThat(post(body.replace("349.99", "0.01"), signature)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(price("AUD-1")).isNull();
    }

    @Test
    void rejectsInvalidProductChanges() {
        String body = event(List.of(product("AUD-1", "", "2026-09-14T10:00:00Z")), List.of());

        assertThat(post(body, sign(body, WEBHOOK_SECRET)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("productChanges[0].title");
    }

    @Test
    void rejectsInvalidPriceChanges() {
        String body = event(List.of(), List.of(priceChange("AUD-1", "-1.00", "2026-09-14T10:00:00Z")));

        assertThat(post(body, sign(body, WEBHOOK_SECRET)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactly("priceChanges[0].price.amount");
    }

    @Test
    void rejectsEventsWithoutChanges() {
        String body = event(List.of(), List.of());

        assertThat(post(body, sign(body, WEBHOOK_SECRET)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("notEmpty");
    }

    @Test
    void rejectsMalformedJson() {
        String body = "{not json";

        assertThat(post(body, sign(body, WEBHOOK_SECRET)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.title").isEqualTo("Invalid update");
    }

    private MvcTestResult post(String body, String signature) {
        var request = mvc.post().uri("/api/v1/product-updates").contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) {
            request.header(ProductWebhookController.SIGNATURE_HEADER, signature);
        }
        return request.exchange();
    }

    private BigDecimal price(String sku) {
        return products.findBySku(sku).orElseThrow().getPriceAmount();
    }

    private static String event(List<String> productChanges, List<String> priceChanges) {
        return """
                {"eventId": "%s", "sentAt": "2026-09-14T10:00:01Z", "productChanges": [%s], "priceChanges": [%s]}
                """.formatted(UUID.randomUUID(), String.join(", ", productChanges), String.join(", ", priceChanges));
    }

    private static String product(String articleNumber, String title, String lastModified) {
        return """
                {"articleNumber": "%s", "title": "%s", "manufacturer": "Fairphone", "categoryPath": "Electronics > Phones",
                 "specs": [{"name": "color", "value": "Moss Green"}], "lastModified": "%s"}
                """.formatted(articleNumber, title, lastModified);
    }

    private static String pricedProduct(String articleNumber, String title, String lastModified, String amount) {
        return """
                {"articleNumber": "%s", "title": "%s", "manufacturer": "Fairphone", "categoryPath": "Electronics > Phones",
                 "specs": [{"name": "color", "value": "Moss Green"}], "lastModified": "%s",
                 "price": {"amount": "%s", "currency": "EUR"}, "priceValidFrom": "%s"}
                """.formatted(articleNumber, title, lastModified, amount, lastModified);
    }

    private static String priceChange(String articleNumber, String amount, String validFrom) {
        return """
                {"articleNumber": "%s", "price": {"amount": "%s", "currency": "EUR"}, "validFrom": "%s"}
                """.formatted(articleNumber, amount, validFrom);
    }

    private static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
