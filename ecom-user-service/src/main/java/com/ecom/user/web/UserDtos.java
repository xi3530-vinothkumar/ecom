package com.ecom.user.web;

import com.ecom.user.domain.Address;
import com.ecom.user.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

final class UserDtos {

    static final String PHONE_PATTERN = "^$|^\\+?[0-9 ()\\-]{5,32}$";

    record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Size(max = 200) String fullName,
            @Size(max = 32) @Pattern(regexp = PHONE_PATTERN) String phone) {
        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + "]";
        }
    }

    record UpdateProfileRequest(
            @Size(min = 1, max = 200) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String fullName,
            @Size(max = 32) @Pattern(regexp = PHONE_PATTERN) String phone) {}

    record UserResponse(UUID id, String email, String fullName, String phone, Set<String> roles, Instant createdAt) {
        static UserResponse of(User u) {
            return new UserResponse(
                    u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), new TreeSet<>(u.getRoles()), u.getCreatedAt());
        }
    }

    record AddressRequest(
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be an ISO 3166-1 alpha-2 code") String countryCode) {}

    record AddressResponse(
            UUID id, String line1, String line2, String city, String postalCode, String countryCode,
            Instant createdAt, Instant updatedAt) {
        static AddressResponse of(Address a) {
            return new AddressResponse(
                    a.getId(), a.getLine1(), a.getLine2(), a.getCity(), a.getPostalCode(), a.getCountryCode(),
                    a.getCreatedAt(), a.getUpdatedAt());
        }
    }

    private UserDtos() {}
}
