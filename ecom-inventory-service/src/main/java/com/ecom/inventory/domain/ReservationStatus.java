package com.ecom.inventory.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Reservation lifecycle. {@code RESERVED -> EXPIRING} is the only transition the sweep performs; it
 * never releases stock. Only Order's decision (confirmed / cancelled) moves a reservation to a
 * terminal state, from either RESERVED or EXPIRING.
 *
 * <pre>
 * RESERVED --sweep--> EXPIRING
 * RESERVED | EXPIRING --order-confirmed--> COMMITTED
 * RESERVED | EXPIRING --order-cancelled--> RELEASED
 * </pre>
 */
public enum ReservationStatus {
    RESERVED,
    EXPIRING,
    COMMITTED,
    RELEASED;

    /** Mixed-line precedence for {@link #aggregate}: the state that is "least safe" for the order wins. */
    private static final List<ReservationStatus> MIXED_PRECEDENCE = List.of(RELEASED, EXPIRING, RESERVED, COMMITTED);

    public boolean isTerminal() {
        return this == COMMITTED || this == RELEASED;
    }

    /** True while the reservation still holds stock ({@code reserved} counts it). */
    public boolean holdsStock() {
        return this == RESERVED || this == EXPIRING;
    }

    public boolean canTransitionTo(ReservationStatus target) {
        return switch (this) {
            case RESERVED -> target == EXPIRING || target == COMMITTED || target == RELEASED;
            case EXPIRING -> target == COMMITTED || target == RELEASED;
            case COMMITTED, RELEASED -> false;
        };
    }

    /** All states from which {@code target} can be reached in one step. */
    public static Set<ReservationStatus> sourcesOf(ReservationStatus target) {
        EnumSet<ReservationStatus> sources = EnumSet.noneOf(ReservationStatus.class);
        Arrays.stream(values()).filter(s -> s.canTransitionTo(target)).forEach(sources::add);
        return sources;
    }

    /**
     * Order-level status from its lines. Lines of one order move together, so normally they are uniform;
     * if they are not (e.g. a sweep raced a commit), report the state least favourable to the order.
     */
    public static ReservationStatus aggregate(Collection<ReservationStatus> lineStatuses) {
        if (lineStatuses.isEmpty()) {
            throw new IllegalArgumentException("No reservation lines");
        }
        return MIXED_PRECEDENCE.stream()
                .filter(lineStatuses::contains)
                .findFirst()
                .orElseThrow();
    }
}
