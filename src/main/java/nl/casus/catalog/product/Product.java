package nl.casus.catalog.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// Read-only. Products are written by CatalogWriter (nl.casus.catalog.update) with conditional SQL.
@Entity
@Immutable
@Table(name = "product")
public class Product {

    @Id
    private Long id;

    private String sku;

    private String name;

    @Column(columnDefinition = "text")
    private String description;

    private String brand;

    private String category;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> attributes;

    private BigDecimal priceAmount;

    private String priceCurrency;

    private Instant priceUpdatedAt;

    private Instant sourceUpdatedAt;

    private Instant createdAt;

    private Instant updatedAt;

    protected Product() {
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getBrand() {
        return brand;
    }

    public String getCategory() {
        return category;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public BigDecimal getPriceAmount() {
        return priceAmount;
    }

    public String getPriceCurrency() {
        return priceCurrency;
    }

    public Instant getPriceUpdatedAt() {
        return priceUpdatedAt;
    }

    public Instant getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
