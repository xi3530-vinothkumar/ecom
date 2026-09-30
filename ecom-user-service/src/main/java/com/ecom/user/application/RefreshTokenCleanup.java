package com.ecom.user.application;

import com.ecom.user.infrastructure.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purges families whose newest token expired more than a day ago. Rotated rows are kept while the
 * family is alive because reuse detection depends on them. Safe to run on every replica.
 */
@Component
public class RefreshTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanup.class);
    private static final Duration GRACE = Duration.ofDays(1);

    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    public RefreshTokenCleanup(RefreshTokenRepository refreshTokens, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ecom.auth.cleanup-interval:PT1H}", initialDelayString = "PT5M")
    @Transactional
    public void purgeExpiredFamilies() {
        int deleted = refreshTokens.deleteFamiliesExpiredBefore(clock.instant().minus(GRACE));
        if (deleted > 0) {
            log.info("Purged {} expired refresh tokens", deleted);
        }
    }
}
