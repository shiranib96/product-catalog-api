package nl.casus.catalog.update;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

// All writes from sources are single statements that only apply newer data. That way it doesn't matter
// in which order the pull and the webhook deliver things, and nothing needs to be locked.
@Repository
class CatalogWriter {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    CatalogWriter(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    // false if we already have the same or newer data for this product
    boolean upsertProductIfNewer(ProductUpdate update) {
        Map<String, String> attributes = update.attributes() == null ? Map.of() : update.attributes();
        return jdbc.sql("""
                        INSERT INTO product (sku, name, description, brand, category, attributes, source_updated_at,
                                             created_at, updated_at)
                        VALUES (:sku, :name, :description, :brand, :category, CAST(:attributes AS jsonb), :modifiedAt,
                                now(), now())
                        ON CONFLICT (sku) DO UPDATE
                           SET name = EXCLUDED.name,
                               description = EXCLUDED.description,
                               brand = EXCLUDED.brand,
                               category = EXCLUDED.category,
                               attributes = EXCLUDED.attributes,
                               source_updated_at = EXCLUDED.source_updated_at,
                               updated_at = now()
                         WHERE product.source_updated_at IS NULL
                            OR product.source_updated_at < EXCLUDED.source_updated_at
                        """)
                .param("sku", update.sku())
                .param("name", update.name())
                .param("description", update.description())
                .param("brand", update.brand())
                .param("category", update.category())
                .param("attributes", jsonMapper.writeValueAsString(attributes))
                .param("modifiedAt", utc(update.modifiedAt()))
                .update() == 1;
    }

    // false if the product doesn't exist or already has the same or a newer price
    boolean updatePriceIfNewer(PriceUpdate update) {
        return jdbc.sql("""
                        UPDATE product
                           SET price_amount = :amount, price_currency = :currency, price_updated_at = :effectiveAt
                         WHERE sku = :sku
                           AND (price_updated_at IS NULL OR price_updated_at < :effectiveAt)
                        """)
                .param("sku", update.sku())
                .param("amount", update.price())
                .param("currency", update.currency())
                .param("effectiveAt", utc(update.effectiveAt()))
                .update() == 1;
    }

    boolean exists(String sku) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM product WHERE sku = :sku)")
                .param("sku", sku)
                .query(Boolean.class)
                .single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
