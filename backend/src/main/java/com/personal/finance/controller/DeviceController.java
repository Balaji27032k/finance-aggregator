package com.personal.finance.controller;

import com.personal.finance.dto.HeartbeatResponse;
import com.personal.finance.dto.RegisterRequest;
import com.personal.finance.dto.RegisterResponse;
import com.personal.finance.security.DevicePrincipal;
import com.personal.finance.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    @PostMapping("/register")
    ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(request));
    }

    @PostMapping("/heartbeat")
    HeartbeatResponse heartbeat(@AuthenticationPrincipal DevicePrincipal principal) {
        return new HeartbeatResponse(service.heartbeat(principal.deviceId()));
    }

    @PostMapping("/revoke")
    ResponseEntity<Void> revoke(@AuthenticationPrincipal DevicePrincipal principal) {
        service.revoke(principal.deviceId());
        return ResponseEntity.noContent().build();
    }
}
