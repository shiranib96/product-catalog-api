package nl.casus.catalog.source;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

// a product in the source's format (see mock-source/server.py), mapped to ours by SourceMapper
// lastModified is about the product data, priceValidFrom about the price: they change independently
record SourceProduct(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String articleNumber,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 10_000) String details,
        @Size(max = 255) String manufacturer,
        @Size(max = 255) String categoryPath,
        @Size(max = 50) List<Spec> specs,
        @Valid SourcePrice price,
        Instant priceValidFrom,
        @NotNull Instant lastModified) {

    record Spec(String name, String value) {
    }
}
