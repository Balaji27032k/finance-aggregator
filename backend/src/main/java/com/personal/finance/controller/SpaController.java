package com.personal.finance.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The dashboard is a single-page app; its client-side routes all serve the same page. */
@Controller
public class SpaController {

    @GetMapping({"/login", "/transactions", "/insights", "/data"})
    String index() {
        return "forward:/index.html";
    }
}
