package com.personal.finance.controller;

import com.personal.finance.dto.Profile;
import com.personal.finance.service.ProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The person's details, edited on the phone (device token) and readable from the dashboard (admin cookie). */
@RestController
public class ProfileController {

    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/profile")
    Profile deviceGet() {
        return service.get();
    }

    @PutMapping("/api/v1/profile")
    Profile devicePut(@Valid @RequestBody Profile profile) {
        service.save(profile);
        return profile;
    }

    @GetMapping("/api/admin/profile")
    Profile adminGet() {
        return service.get();
    }

    @PutMapping("/api/admin/profile")
    Profile adminPut(@Valid @RequestBody Profile profile) {
        service.save(profile);
        return profile;
    }
}
