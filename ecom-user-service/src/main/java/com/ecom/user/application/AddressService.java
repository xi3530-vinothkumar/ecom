package com.ecom.user.application;

import com.ecom.platform.core.EcomException;
import com.ecom.user.domain.Address;
import com.ecom.user.domain.AddressDetails;
import com.ecom.user.infrastructure.AddressRepository;
import com.ecom.user.infrastructure.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Address book; every operation is scoped to the owning user (foreign ids look like 404). */
@Service
public class AddressService {

    static final int MAX_ADDRESSES_PER_USER = 20;

    private final AddressRepository addresses;
    private final UserRepository users;
    private final Clock clock;

    public AddressService(AddressRepository addresses, UserRepository users, Clock clock) {
        this.addresses = addresses;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Address> list(UUID userId) {
        return addresses.findByUserIdOrderByCreatedAtAsc(userId);
    }

    @Transactional(readOnly = true)
    public Address get(UUID userId, UUID addressId) {
        return addresses.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> EcomException.notFound("Address not found"));
    }

    @Transactional
    public Address create(UUID userId, AddressDetails details) {
        if (!users.existsById(userId)) {
            throw EcomException.notFound("User not found");
        }
        if (addresses.countByUserId(userId) >= MAX_ADDRESSES_PER_USER) {
            throw EcomException.unprocessable("At most " + MAX_ADDRESSES_PER_USER + " addresses per account");
        }
        return addresses.save(new Address(UUID.randomUUID(), userId, details, clock.instant()));
    }

    @Transactional
    public Address update(UUID userId, UUID addressId, AddressDetails details) {
        Address address = get(userId, addressId);
        address.update(details, clock.instant());
        return address;
    }

    @Transactional
    public void delete(UUID userId, UUID addressId) {
        addresses.delete(get(userId, addressId));
    }
}
