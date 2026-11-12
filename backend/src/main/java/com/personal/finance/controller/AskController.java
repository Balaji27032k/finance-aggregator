package com.personal.finance.controller;

import com.personal.finance.ai.AskDtos;
import com.personal.finance.ai.AskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Plain-words questions about spending, from the dashboard (admin cookie) and from the phone (device token). */
@RestController
public class AskController {

    private final AskService service;

    public AskController(AskService service) {
        this.service = service;
    }

    @PostMapping("/api/admin/ask")
    AskDtos.Response adminAsk(@Valid @RequestBody AskDtos.Request request) {
        return service.ask(request);
    }

    @PostMapping("/api/v1/ask")
    AskDtos.Response deviceAsk(@Valid @RequestBody AskDtos.Request request) {
        return service.ask(request);
    }
}
