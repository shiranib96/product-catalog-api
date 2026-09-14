package nl.casus.catalog.source;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("catalog.source")
@Validated
public record SourceProperties(
        @NotBlank String baseUrl,
        @Min(1) @Max(200) int pageSize,
        @NotNull Duration syncInterval,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Valid @NotNull Webhook webhook) {

    public record Webhook(@NotBlank String callbackUrl, @NotBlank String secret) {
    }
}
