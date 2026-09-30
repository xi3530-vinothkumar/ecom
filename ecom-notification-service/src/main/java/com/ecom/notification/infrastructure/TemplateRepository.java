package com.ecom.notification.infrastructure;

import com.ecom.notification.domain.Channel;
import com.ecom.notification.domain.NotificationTemplate;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TemplateRepository {

    private final JdbcTemplate jdbc;

    public TemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<NotificationTemplate> find(String key) {
        return jdbc.query("SELECT key, channel, subject, body FROM notification_templates WHERE key = ?",
                        (rs, i) -> new NotificationTemplate(
                                rs.getString(1), Channel.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4)),
                        key)
                .stream()
                .findFirst();
    }
}
