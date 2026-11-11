package com.personal.finance.controller;

import com.personal.finance.dto.StagingBatchRequest;
import com.personal.finance.dto.StagingBatchResponse;
import com.personal.finance.security.DevicePrincipal;
import com.personal.finance.service.MessageStagingService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staging")
public class StagingController {

    private final MessageStagingService service;

    public StagingController(MessageStagingService service) {
        this.service = service;
    }

    @PostMapping("/batch")
    StagingBatchResponse batch(@AuthenticationPrincipal DevicePrincipal principal,
                               @Valid @RequestBody StagingBatchRequest request) {
        return service.store(principal.deviceId(), request.messages());
    }
}
