package com.ecom.user.infrastructure;

import com.ecom.user.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** {@code email} must already be normalized (see {@link com.ecom.user.domain.Emails}). */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
