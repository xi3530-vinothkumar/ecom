package com.ecom.search.application;

import com.ecom.search.infrastructure.ElasticsearchProductIndex;
import com.ecom.search.infrastructure.SearchProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly removal of docs that never got (or lost) catalog data: stock stubs for SKUs whose product
 * never arrived and delete tombstones. Idempotent, so running on every replica is harmless.
 */
@Component
public class OrphanPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(OrphanPurgeJob.class);

    private final ElasticsearchProductIndex index;
    private final SearchProperties props;
    private final Clock clock;

    public OrphanPurgeJob(ElasticsearchProductIndex index, SearchProperties props, Clock clock) {
        this.index = index;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "${ecom.search.purge-cron}")
    public void purge() {
        try {
            long deleted = index.purgeOrphans(clock.instant().minus(props.orphanTtl()));
            log.info("Purged {} orphan search docs older than {}", deleted, props.orphanTtl());
        } catch (RuntimeException e) {
            log.error("Orphan purge failed; will retry on next schedule", e);
        }
    }
}
