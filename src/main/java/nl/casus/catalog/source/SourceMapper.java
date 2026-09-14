package nl.casus.catalog.source;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import nl.casus.catalog.update.PriceUpdate;
import nl.casus.catalog.update.ProductUpdate;

// the only place that knows both the source's format and ours
final class SourceMapper {

    private SourceMapper() {
    }

    static ProductUpdate toProductUpdate(SourceProduct product) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (product.specs() != null) {
            for (var spec : product.specs()) {
                if (spec != null && spec.name() != null && !spec.name().isBlank() && spec.value() != null) {
                    attributes.put(spec.name().trim(), spec.value().trim());
                }
            }
        }
        return new ProductUpdate(
                product.articleNumber(),
                product.title().trim(),
                product.details(),
                product.manufacturer(),
                product.categoryPath(),
                attributes,
                product.lastModified());
    }

    static Optional<PriceUpdate> toPriceUpdate(SourceProduct product) {
        if (product.price() == null || product.priceValidFrom() == null) {
            return Optional.empty();
        }
        return Optional.of(new PriceUpdate(product.articleNumber(), product.price().amount(),
                product.price().currency(), product.priceValidFrom()));
    }

    static PriceUpdate toPriceUpdate(SourceEvent.PriceChange change) {
        return new PriceUpdate(change.articleNumber(), change.price().amount(), change.price().currency(),
                change.validFrom());
    }
}
