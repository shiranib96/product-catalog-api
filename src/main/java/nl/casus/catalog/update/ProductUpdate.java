package nl.casus.catalog.update;

import java.time.Instant;
import java.util.Map;

// everything about a product except its price. modifiedAt: when it was last changed at the source
public record ProductUpdate(
        String sku,
        String name,
        String description,
        String brand,
        String category,
        Map<String, String> attributes,
        Instant modifiedAt) {
}
