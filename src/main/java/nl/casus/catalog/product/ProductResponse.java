package nl.casus.catalog.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record ProductResponse(
        String sku,
        String name,
        String description,
        String brand,
        String category,
        Map<String, String> attributes,
        Price price,
        Instant createdAt,
        Instant updatedAt) {

    public record Price(BigDecimal amount, String currency, Instant updatedAt) {
    }

    static ProductResponse from(Product product) {
        Price price = product.getPriceAmount() == null ? null
                : new Price(product.getPriceAmount(), product.getPriceCurrency(), product.getPriceUpdatedAt());
        return new ProductResponse(
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getBrand(),
                product.getCategory(),
                Map.copyOf(product.getAttributes()),
                price,
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
