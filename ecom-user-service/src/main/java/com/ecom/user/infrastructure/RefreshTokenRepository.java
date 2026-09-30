package com.ecom.user.infrastructure;

import com.ecom.user.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Row lock serializes concurrent refreshes of the same token (the loser sees ROTATED). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

    Optional<RefreshToken> findByTokenHash(String hash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /** Deletes whole families whose newest token expired before {@code cutoff}. */
    @Modifying
    @Query(value = """
            delete from refresh_tokens where family_id in (
                select family_id from refresh_tokens group by family_id having max(expires_at) < :cutoff)
            """, nativeQuery = true)
    int deleteFamiliesExpiredBefore(@Param("cutoff") Instant cutoff);
}
