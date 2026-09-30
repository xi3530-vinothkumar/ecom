package com.ecom.user.web;

import com.ecom.user.application.AddressService;
import com.ecom.user.domain.AddressDetails;
import com.ecom.user.web.UserDtos.AddressRequest;
import com.ecom.user.web.UserDtos.AddressResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me/addresses")
class AddressController {

    private final AddressService addresses;

    AddressController(AddressService addresses) {
        this.addresses = addresses;
    }

    @GetMapping
    List<AddressResponse> list() {
        return addresses.list(CurrentUser.id()).stream().map(AddressResponse::of).toList();
    }

    @GetMapping("/{addressId}")
    AddressResponse get(@PathVariable UUID addressId) {
        return AddressResponse.of(addresses.get(CurrentUser.id(), addressId));
    }

    @PostMapping
    ResponseEntity<AddressResponse> create(@Valid @RequestBody AddressRequest req) {
        var created = addresses.create(CurrentUser.id(), details(req));
        return ResponseEntity.created(URI.create("/users/me/addresses/" + created.getId()))
                .body(AddressResponse.of(created));
    }

    @PutMapping("/{addressId}")
    AddressResponse update(@PathVariable UUID addressId, @Valid @RequestBody AddressRequest req) {
        return AddressResponse.of(addresses.update(CurrentUser.id(), addressId, details(req)));
    }

    @DeleteMapping("/{addressId}")
    ResponseEntity<Void> delete(@PathVariable UUID addressId) {
        addresses.delete(CurrentUser.id(), addressId);
        return ResponseEntity.noContent().build();
    }

    private static AddressDetails details(AddressRequest r) {
        return new AddressDetails(r.line1(), r.line2(), r.city(), r.postalCode(), r.countryCode());
    }
}
