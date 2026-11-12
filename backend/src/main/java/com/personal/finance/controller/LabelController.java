package com.personal.finance.controller;

import com.personal.finance.dto.LabelItem;
import com.personal.finance.security.DevicePrincipal;
import com.personal.finance.service.LabelService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions/labels")
public class LabelController {

    private final LabelService service;

    public LabelController(LabelService service) {
        this.service = service;
    }

    @PostMapping
    LabelItem.Result apply(@AuthenticationPrincipal DevicePrincipal principal, @Valid @RequestBody LabelItem.Batch request) {
        return service.apply(principal.deviceId(), request.labels());
    }
}
