package nl.casus.catalog.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// turned off in the tests, they call SourceSync themselves
@Component
@ConditionalOnProperty(name = "catalog.source.scheduled-sync", havingValue = "true", matchIfMissing = true)
class SourceSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SourceSyncScheduler.class);

    private final SourceSync sourceSync;

    SourceSyncScheduler(SourceSync sourceSync) {
        this.sourceSync = sourceSync;
    }

    // runs right after startup and then every sync-interval
    @Scheduled(fixedDelayString = "${catalog.source.sync-interval}")
    void synchronize() {
        try {
            sourceSync.synchronize();
        } catch (RuntimeException e) {
            log.warn("Catalog sync failed, trying again next interval: {}", e.toString());
        }
    }
}
