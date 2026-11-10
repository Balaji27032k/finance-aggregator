package com.personal.finance.controller;

import com.personal.finance.dto.BatchIngestRequest;
import com.personal.finance.dto.BatchIngestResponse;
import com.personal.finance.dto.EventItem;
import com.personal.finance.dto.EventStatusResponse;
import com.personal.finance.dto.IngestResult;
import com.personal.finance.security.DevicePrincipal;
import com.personal.finance.service.EventIngestionService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventIngestionService service;

    public EventController(EventIngestionService service) {
        this.service = service;
    }

    @PostMapping("/ingest")
    IngestResult ingest(@AuthenticationPrincipal DevicePrincipal principal, @RequestBody EventItem item) {
        return service.ingest(principal.deviceId(), item);
    }

    /** Items are validated one by one so a single bad event does not block the rest of the batch. */
    @PostMapping("/batch")
    BatchIngestResponse batch(@AuthenticationPrincipal DevicePrincipal principal,
                              @Valid @RequestBody BatchIngestRequest request) {
        return new BatchIngestResponse(service.ingestBatch(principal.deviceId(), request.events()));
    }

    @GetMapping("/{eventId}")
    EventStatusResponse status(@AuthenticationPrincipal DevicePrincipal principal, @PathVariable Long eventId) {
        return service.status(principal.deviceId(), eventId);
    }
}
