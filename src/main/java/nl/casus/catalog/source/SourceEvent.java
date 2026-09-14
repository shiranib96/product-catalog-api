package nl.casus.catalog.source;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

// body of the source's webhook call: changed (or new) products and/or changed prices
record SourceEvent(
        String eventId,
        Instant sentAt,
        @Size(max = 1000) List<@Valid @NotNull SourceProduct> productChanges,
        @Size(max = 1000) List<@Valid @NotNull PriceChange> priceChanges) {

    @AssertTrue(message = "must contain productChanges or priceChanges")
    public boolean isNotEmpty() {
        return !products().isEmpty() || !prices().isEmpty();
    }

    List<SourceProduct> products() {
        return productChanges == null ? List.of() : productChanges;
    }

    List<PriceChange> prices() {
        return priceChanges == null ? List.of() : priceChanges;
    }

    record PriceChange(
            @NotBlank String articleNumber,
            @NotNull @Valid SourcePrice price,
            @NotNull Instant validFrom) {
    }
}
