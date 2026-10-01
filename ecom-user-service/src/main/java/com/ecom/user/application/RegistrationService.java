package com.ecom.user.application;

import com.ecom.common.events.EventTopics;
import com.ecom.common.events.payload.UserRegisteredPayload;
import com.ecom.platform.core.EcomException;
import com.ecom.platform.kafka.OutboxWriter;
import com.ecom.user.domain.Emails;
import com.ecom.user.domain.LoginFailures;
import com.ecom.user.domain.Roles;
import com.ecom.user.domain.User;
import com.ecom.user.infrastructure.LoginFailuresRepository;
import com.ecom.user.infrastructure.UserRepository;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    public record Registration(String email, String password, String fullName, String phone) {
        @Override
        public String toString() {
            return "Registration[email=" + email + "]";
        }
    }

    private final UserRepository users;
    private final LoginFailuresRepository loginFailures;
    private final PasswordEncoder passwordEncoder;
    private final OutboxWriter outbox;
    private final Clock clock;

    public RegistrationService(
            UserRepository users,
            LoginFailuresRepository loginFailures,
            PasswordEncoder passwordEncoder,
            OutboxWriter outbox,
            Clock clock) {
        this.users = users;
        this.loginFailures = loginFailures;
        this.passwordEncoder = passwordEncoder;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public User register(Registration r) {
        User user = create(r, Set.of(Roles.CUSTOMER));
        String userId = user.getId().toString();
        outbox.publish(EventTopics.USER_REGISTERED, userId, new UserRegisteredPayload(userId));
        return user;
    }

    /**
     * Idempotent: creates the admin if missing, otherwise only ensures the roles (an existing
     * password is never overwritten, so rotating ADMIN_PASSWORD requires a real password change).
     * Returns true when the account was created.
     */
    @Transactional
    public boolean ensureAdmin(String email, String password) {
        var existing = users.findByEmail(Emails.normalize(email));
        if (existing.isPresent()) {
            existing.get().grantRole(Roles.ADMIN);
            existing.get().grantRole(Roles.CUSTOMER);
            return false;
        }
        create(new Registration(email, password, "Administrator", null), Set.of(Roles.ADMIN, Roles.CUSTOMER));
        return true;
    }

    private User create(Registration r, Set<String> roles) {
        String email = Emails.normalize(r.email());
        if (users.existsByEmail(email)) {
            throw EcomException.conflict("An account with this email already exists");
        }
        User user = new User(
                UUID.randomUUID(), email, passwordEncoder.encode(r.password()), r.fullName().trim(),
                blankToNull(r.phone()), roles, clock.instant());
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException _) {
            // lost a race with a concurrent registration of the same address (unique lower(email))
            throw EcomException.conflict("An account with this email already exists");
        }
        loginFailures.save(new LoginFailures(user.getId()));
        return user;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
