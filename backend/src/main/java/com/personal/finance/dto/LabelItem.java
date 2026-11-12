package com.personal.finance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** What the user says one captured event was. A null category, label and splits clear an earlier correction. */
public record LabelItem(
        @NotBlank @Size(max = 64) String localEventId,
        @Size(max = 40) String category,
        @Size(max = 100) String label,
        @Size(max = 6) List<@Valid @NotNull Part> splits) {

    /** One share of a payment that the user split across categories. */
    public record Part(
            @NotBlank @Size(max = 40) String category,
            @Size(max = 60) String name,
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount) {
    }

    public record Batch(@NotNull @Size(min = 1, max = 200) List<@Valid @NotNull LabelItem> labels) {
    }

    public record Result(int updated, List<String> missing) {
    }
}
