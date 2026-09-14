package nl.casus.catalog.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import nl.casus.catalog.source.SourceProduct.Spec;
import nl.casus.catalog.update.PriceUpdate;
import nl.casus.catalog.update.ProductUpdate;
import org.junit.jupiter.api.Test;

class SourceMapperTest {

    private static final Instant MODIFIED = Instant.parse("2026-08-20T10:00:00Z");
    private static final Instant VALID_FROM = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    void mapsSourceFieldsToOurs() {
        var update = SourceMapper.toProductUpdate(product(List.of(new Spec("color", "Black")), null, null));

        assertThat(update).isEqualTo(new ProductUpdate("AUD-1", "Sony WH-1000XM5", "Noise cancelling headphones",
                "Sony", "Electronics > Audio > Headphones", Map.of("color", "Black"), MODIFIED));
    }

    @Test
    void cleansUpSpecs() {
        var specs = Arrays.asList(
                new Spec(" color ", " Black "),
                new Spec("", "no name"),
                new Spec("size", null),
                null,
                new Spec("color", "Blue"));

        assertThat(SourceMapper.toProductUpdate(product(specs, null, null)).attributes())
                .isEqualTo(Map.of("color", "Blue"));
    }

    @Test
    void onlyHasAPriceWhenAmountAndDateAreKnown() {
        var price = new SourcePrice(new BigDecimal("379.00"), "EUR");

        assertThat(SourceMapper.toPriceUpdate(product(List.of(), null, null))).isEmpty();
        assertThat(SourceMapper.toPriceUpdate(product(List.of(), price, null))).isEmpty();
        assertThat(SourceMapper.toPriceUpdate(product(List.of(), price, VALID_FROM)))
                .contains(new PriceUpdate("AUD-1", new BigDecimal("379.00"), "EUR", VALID_FROM));
    }

    @Test
    void mapsWebhookPriceChanges() {
        var change = new SourceEvent.PriceChange("AUD-1", new SourcePrice(new BigDecimal("349.99"), "EUR"),
                VALID_FROM);

        assertThat(SourceMapper.toPriceUpdate(change))
                .isEqualTo(new PriceUpdate("AUD-1", new BigDecimal("349.99"), "EUR", VALID_FROM));
    }

    private static SourceProduct product(List<Spec> specs, SourcePrice price, Instant priceValidFrom) {
        return new SourceProduct("AUD-1", " Sony WH-1000XM5 ", "Noise cancelling headphones", "Sony",
                "Electronics > Audio > Headphones", specs, price, priceValidFrom, MODIFIED);
    }
}
