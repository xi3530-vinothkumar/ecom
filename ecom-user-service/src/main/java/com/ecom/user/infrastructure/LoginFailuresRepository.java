package com.ecom.user.infrastructure;

import com.ecom.user.domain.LoginFailures;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginFailuresRepository extends JpaRepository<LoginFailures, UUID> {

    /** Locks the account's counter for the whole login attempt so parallel guesses are counted. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from LoginFailures f where f.userId = :userId")
    Optional<LoginFailures> findForUpdate(@Param("userId") UUID userId);
}
