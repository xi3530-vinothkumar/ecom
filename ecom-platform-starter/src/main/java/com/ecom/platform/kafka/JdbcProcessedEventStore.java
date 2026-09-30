package com.ecom.platform.kafka;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcProcessedEventStore implements ProcessedEventStore {

    private final JdbcTemplate jdbc;

    public JdbcProcessedEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean markProcessed(UUID eventId, String consumer) {
        return jdbc.update(
                        "INSERT INTO processed_events (event_id, consumer) VALUES (?, ?) ON CONFLICT DO NOTHING",
                        eventId, consumer)
                == 1;
    }
}
