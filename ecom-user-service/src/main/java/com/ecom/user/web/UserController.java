package com.ecom.user.web;

import com.ecom.user.application.ProfileService;
import com.ecom.user.application.RegistrationService;
import com.ecom.user.web.UserDtos.RegisterRequest;
import com.ecom.user.web.UserDtos.UpdateProfileRequest;
import com.ecom.user.web.UserDtos.UserResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
class UserController {

    private final RegistrationService registration;
    private final ProfileService profiles;

    UserController(RegistrationService registration, ProfileService profiles) {
        this.registration = registration;
        this.profiles = profiles;
    }

    @PostMapping
    ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest req) {
        var user = registration.register(
                new RegistrationService.Registration(req.email(), req.password(), req.fullName(), req.phone()));
        return ResponseEntity.created(URI.create("/users/me")).body(UserResponse.of(user));
    }

    @GetMapping("/me")
    UserResponse me() {
        return UserResponse.of(profiles.get(CurrentUser.id()));
    }

    @PatchMapping("/me")
    UserResponse updateMe(@Valid @RequestBody UpdateProfileRequest req) {
        return UserResponse.of(profiles.update(CurrentUser.id(), req.fullName(), req.phone()));
    }
}
