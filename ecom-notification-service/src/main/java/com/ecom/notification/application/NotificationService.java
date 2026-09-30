package com.ecom.notification.application;

import com.ecom.notification.domain.NotificationStatus;
import com.ecom.notification.domain.NotificationTemplate;
import com.ecom.notification.domain.OutgoingNotification;
import com.ecom.notification.domain.RecipientMasking;
import com.ecom.notification.infrastructure.NotificationLogRepository;
import com.ecom.notification.infrastructure.NotificationSender;
import com.ecom.notification.infrastructure.TemplateRepository;
import com.ecom.notification.infrastructure.UserDirectory;
import com.ecom.user.proto.v1.User;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs inside the IdempotentConsumer transaction (dedup row + SENT log row commit together).
 *
 * <p>Delivery is at-least-once: the send happens before that commit, so if the commit fails after a
 * successful send (DB outage, crash) the event is redelivered and the user may get a duplicate. A
 * send failure throws, rolling back the dedup row, so the container retries (1s/5s/15s) and then
 * dead-letters; each failed attempt is audited as a FAILED row in its own transaction.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final TemplateRepository templates;
    private final NotificationLogRepository logRows;
    private final UserDirectory users;
    private final NotificationSender sender;
    private final TransactionTemplate independentTx;
    private final Clock clock;

    public NotificationService(
            TemplateRepository templates,
            NotificationLogRepository logRows,
            UserDirectory users,
            NotificationSender sender,
            PlatformTransactionManager txm,
            Clock clock) {
        this.templates = templates;
        this.logRows = logRows;
        this.users = users;
        this.sender = sender;
        this.independentTx = new TransactionTemplate(txm);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public void notify(UUID eventId, String userId, String templateKey, Map<String, String> eventVars) {
        NotificationTemplate template = templates.find(templateKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown notification template " + templateKey));

        Optional<User> user = users.find(userId);
        if (user.isEmpty() || user.get().getEmail().isBlank()) {
            // user erased or without contact details: retrying cannot help, record and move on
            String reason = user.isEmpty() ? "user not found" : "no email on file";
            logRows.insert(eventId, userId, templateKey, template.channel(), null, NotificationStatus.FAILED, reason,
                    Instant.now(clock));
            log.warn("Skipping {} for user {}: {}", templateKey, userId, reason);
            return;
        }

        String recipient = user.get().getEmail();
        Map<String, String> vars = new HashMap<>(eventVars);
        String fullName = user.get().getFullName();
        vars.put("name", fullName.isBlank() ? "there" : fullName);
        NotificationTemplate.Rendered rendered = template.render(vars);
        OutgoingNotification outgoing =
                new OutgoingNotification(template.channel(), recipient, templateKey, rendered.subject(), rendered.body());
        String masked = RecipientMasking.mask(recipient);

        try {
            sender.send(outgoing);
        } catch (RuntimeException e) {
            independentTx.executeWithoutResult(s -> logRows.insert(eventId, userId, templateKey, template.channel(),
                    masked, NotificationStatus.FAILED, e.toString(), Instant.now(clock)));
            log.warn("Sending {} to {} failed; event will be retried: {}", templateKey, masked, e.toString());
            throw e;
        }
        logRows.insert(eventId, userId, templateKey, template.channel(), masked, NotificationStatus.SENT, null,
                Instant.now(clock));
    }
}
