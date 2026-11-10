package com.personal.finance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BatchIngestRequest(@NotNull @Size(min = 1, max = BatchIngestRequest.MAX_ITEMS) List<EventItem> events) {

    public static final int MAX_ITEMS = 100;
}
