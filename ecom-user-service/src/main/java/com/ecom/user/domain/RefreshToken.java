package com.ecom.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One link in a refresh-token family. A family starts at login; every refresh rotates the
 * presented token (revoked + replaced_by) and appends a new one. Presenting a token that was
 * already rotated means two parties hold the family, so the whole family is revoked.
 *
 * <p>{@code familyExpiresAt} is the family's absolute end, fixed at login and copied into every
 * successor; each token's {@code expiresAt} never exceeds it, so refreshing cannot extend a session
 * forever.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    public enum Status {
        ACTIVE,
        /** Already exchanged for a successor: presenting it again is a reuse (theft) signal. */
        ROTATED,
        REVOKED,
        EXPIRED
    }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Null only for rows written by an instance predating V2 during a rolling deploy. */
    @Column(name = "family_expires_at")
    private Instant familyExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    protected RefreshToken() {}

    public RefreshToken(
            UUID id, UUID userId, String tokenHash, UUID familyId, Instant createdAt, Instant expiresAt,
            Instant familyExpiresAt) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.familyId = Objects.requireNonNull(familyId);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.familyExpiresAt = Objects.requireNonNull(familyExpiresAt);
        if (expiresAt.isAfter(familyExpiresAt)) {
            throw new IllegalArgumentException("Refresh token cannot outlive its family");
        }
    }

    public Status statusAt(Instant now) {
        if (revokedAt != null) {
            return replacedBy != null ? Status.ROTATED : Status.REVOKED;
        }
        return now.isBefore(expiresAt) ? Status.ACTIVE : Status.EXPIRED;
    }

    /** Marks this token as exchanged for {@code successorId}. Only an ACTIVE token may rotate. */
    public void rotateTo(UUID successorId, Instant now) {
        if (statusAt(now) != Status.ACTIVE) {
            throw new IllegalStateException("Only an active refresh token can be rotated");
        }
        this.revokedAt = now;
        this.replacedBy = Objects.requireNonNull(successorId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getFamilyExpiresAt() {
        return familyExpiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }
}
