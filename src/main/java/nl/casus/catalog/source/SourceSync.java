package nl.casus.catalog.source;

import static java.util.stream.Collectors.joining;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import nl.casus.catalog.update.UpdateService;
import nl.casus.catalog.update.UpdateService.Outcome;
import nl.casus.catalog.update.UpdateService.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

// Pulls the whole catalog (product data and prices) from the source into our own model. Only newer
// data is applied, so this also fixes anything a lost webhook call left behind.
@Component
public class SourceSync {

    private static final Logger log = LoggerFactory.getLogger(SourceSync.class);

    private final SourceClient client;
    private final SourceProperties properties;
    private final UpdateService updates;
    private final Validator validator;

    SourceSync(SourceClient client, SourceProperties properties, UpdateService updates, Validator validator) {
        this.client = client;
        this.properties = properties;
        this.updates = updates;
        this.validator = validator;
    }

    public SyncResult synchronize() {
        registerWebhook();

        int applied = 0;
        int unchanged = 0;
        int skipped = 0;
        int page = 0;
        int totalPages;
        do {
            SourceProductPage result = client.fetchProducts(page, properties.pageSize());
            if (result == null || result.items() == null) {
                throw new IllegalStateException("Source returned an empty response for page " + page);
            }
            for (SourceProduct product : result.items()) {
                if (product == null) {
                    skipped++;
                    continue;
                }
                Set<ConstraintViolation<SourceProduct>> violations = validator.validate(product);
                if (!violations.isEmpty()) {
                    skipped++;
                    log.warn("Skipping source product {}: {}", product.articleNumber(), describe(violations));
                    continue;
                }
                if (updates.applyProduct(SourceMapper.toProductUpdate(product), Channel.SYNC) == Outcome.APPLIED) {
                    applied++;
                } else {
                    unchanged++;
                }
                SourceMapper.toPriceUpdate(product).ifPresent(price -> updates.applyPrice(price, Channel.SYNC));
            }
            totalPages = result.totalPages();
            page++;
        } while (page < totalPages);

        log.info("Synced catalog with source: {} new or changed, {} unchanged, {} skipped", applied, unchanged, skipped);
        return new SyncResult(applied, unchanged, skipped);
    }

    // Registering every time is harmless (the source keys webhooks on the url) and restores the
    // registration if the source lost it. If it fails we still pull, the sync then keeps things right.
    private void registerWebhook() {
        try {
            client.registerWebhook(properties.webhook().callbackUrl(), properties.webhook().secret());
        } catch (RestClientException e) {
            log.warn("Could not register our webhook with the source, syncing anyway: {}", e.getMessage());
        }
    }

    private static String describe(Set<ConstraintViolation<SourceProduct>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .sorted()
                .collect(joining(", "));
    }

    // applied: new or changed products, unchanged: we already had this (or newer) data
    public record SyncResult(int applied, int unchanged, int skipped) {
    }
}
