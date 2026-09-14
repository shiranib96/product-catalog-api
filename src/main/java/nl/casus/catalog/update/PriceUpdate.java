package nl.casus.catalog.update;

import java.math.BigDecimal;
import java.time.Instant;

// effectiveAt: when the price was set at the source
public record PriceUpdate(String sku, BigDecimal price, String currency, Instant effectiveAt) {
}
