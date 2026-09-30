package com.ecom.notification.infrastructure;

import com.ecom.notification.domain.Channel;
import com.ecom.notification.domain.NotificationStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationLogRepository {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbc;

    public NotificationLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID eventId,
            String userId,
            String templateKey,
            Channel channel,
            String recipientMasked,
            NotificationStatus status,
            String error,
            Instant at) {
        jdbc.update("""
                INSERT INTO notification_log
                    (id, event_id, user_id, template_key, channel, recipient_masked, status, error, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), eventId, userId, templateKey, channel.name(), recipientMasked, status.name(),
                truncate(error), Timestamp.from(at));
    }

    private static String truncate(String error) {
        return error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
