package com.personal.finance.controller;

import com.personal.finance.ai.MarketRates;
import com.personal.finance.ai.RatesService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The latest interest rates for each kind of investment, found on the web on request. */
@RestController
public class RatesController {

    private final RatesService service;

    public RatesController(RatesService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/rates")
    MarketRates deviceGet() {
        return service.current().orElseGet(() -> MarketRates.unavailable("No rates fetched yet. Tap Fetch latest rates."));
    }

    @PostMapping("/api/v1/rates/refresh")
    MarketRates deviceRefresh() {
        return service.refresh();
    }

    @GetMapping("/api/admin/rates")
    MarketRates adminGet() {
        return deviceGet();
    }

    @PostMapping("/api/admin/rates/refresh")
    MarketRates adminRefresh() {
        return service.refresh();
    }
}
