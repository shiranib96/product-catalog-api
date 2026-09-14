package nl.casus.catalog.update;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import nl.casus.catalog.update.UpdateResult.Counts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Applies product and price updates from the external source. Newer data always wins, whether it came
// in through the webhook or the periodic sync.
@Service
public class UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);

    private final CatalogWriter writer;
    private final MeterRegistry meterRegistry;

    UpdateService(CatalogWriter writer, MeterRegistry meterRegistry) {
        this.writer = writer;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public Outcome applyProduct(ProductUpdate update, Channel channel) {
        var outcome = writer.upsertProductIfNewer(update) ? Outcome.APPLIED : Outcome.STALE;
        record("product", channel, outcome, update.modifiedAt());
        return outcome;
    }

    @Transactional
    public Outcome applyPrice(PriceUpdate update, Channel channel) {
        Outcome outcome;
        if (writer.updatePriceIfNewer(update)) {
            outcome = Outcome.APPLIED;
        } else if (writer.exists(update.sku())) {
            outcome = Outcome.STALE;
        } else {
            outcome = Outcome.UNKNOWN_PRODUCT;
        }
        record("price", channel, outcome, update.effectiveAt());
        return outcome;
    }

    // products first, so a new product and its price can come in the same call
    @Transactional
    public UpdateResult applyAll(List<ProductUpdate> products, List<PriceUpdate> prices, Channel channel) {
        int productsApplied = 0;
        int productsStale = 0;
        for (ProductUpdate product : products) {
            if (applyProduct(product, channel) == Outcome.APPLIED) {
                productsApplied++;
            } else {
                productsStale++;
            }
        }
        int pricesApplied = 0;
        int pricesStale = 0;
        List<String> unknownProducts = new ArrayList<>();
        for (PriceUpdate price : prices) {
            switch (applyPrice(price, channel)) {
                case APPLIED -> pricesApplied++;
                case STALE -> pricesStale++;
                case UNKNOWN_PRODUCT -> unknownProducts.add(price.sku());
            }
        }
        if (!unknownProducts.isEmpty()) {
            // not a problem: if the source has these products the next sync imports them with their price
            log.warn("Got prices for unknown products {}", unknownProducts);
        }
        return new UpdateResult(new Counts(productsApplied, productsStale), new Counts(pricesApplied, pricesStale),
                unknownProducts);
    }

    // updates applied through the sync (after the first import) mean a webhook call got lost somewhere
    private void record(String type, Channel channel, Outcome outcome, Instant changedAt) {
        Counter.builder("catalog.updates")
                .description("Updates from the source by type, channel and outcome")
                .tag("type", type)
                .tag("channel", channel.name().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry)
                .increment();
        if (outcome == Outcome.APPLIED && channel == Channel.WEBHOOK) {
            Timer.builder("catalog.update.lag")
                    .description("Time between a change at the source and the webhook applying it here")
                    .tag("type", type)
                    .register(meterRegistry)
                    .record(Duration.between(changedAt, Instant.now()));
        }
    }

    // how an update reached us: pushed to our webhook, or picked up by the periodic sync
    public enum Channel {
        WEBHOOK, SYNC
    }

    public enum Outcome {
        APPLIED, STALE, UNKNOWN_PRODUCT
    }
}
