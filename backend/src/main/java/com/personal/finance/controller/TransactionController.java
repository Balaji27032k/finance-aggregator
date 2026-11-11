package com.personal.finance.controller;

import com.personal.finance.dto.TransactionView;
import com.personal.finance.service.TransactionQueryService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private static final int MAX_LIMIT = 1000;

    private final TransactionQueryService service;

    public TransactionController(TransactionQueryService service) {
        this.service = service;
    }

    /** Dates are UTC days, both ends inclusive; without them every transaction is considered, newest first. */
    @GetMapping
    List<TransactionView> list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                               @RequestParam(defaultValue = "200") int limit) {
        Instant start = from == null ? Instant.EPOCH : from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = to == null ? Instant.parse("9999-01-01T00:00:00Z") : to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return service.list(start, end, Math.max(1, Math.min(limit, MAX_LIMIT)));
    }
}
