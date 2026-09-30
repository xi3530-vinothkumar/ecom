package com.ecom.user.application;

import com.ecom.platform.core.EcomException;
import com.ecom.user.domain.User;
import com.ecom.user.infrastructure.UserRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    private final UserRepository users;
    private final Clock clock;

    public ProfileService(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public User get(UUID userId) {
        return users.findById(userId).orElseThrow(() -> EcomException.notFound("User not found"));
    }

    /** Partial update: null = unchanged; blank phone clears it. */
    @Transactional
    public User update(UUID userId, String fullName, String phone) {
        User user = get(userId);
        String newName = fullName == null ? user.getFullName() : fullName.trim();
        String newPhone = phone == null ? user.getPhone() : RegistrationService.blankToNull(phone);
        user.updateProfile(newName, newPhone, clock.instant());
        return user;
    }
}
