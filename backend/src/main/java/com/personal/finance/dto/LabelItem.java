package com.personal.finance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** What the user says one captured event was. A null category and label clear an earlier correction. */
public record LabelItem(
        @NotBlank @Size(max = 64) String localEventId,
        @Size(max = 40) String category,
        @Size(max = 100) String label) {

    public record Batch(@NotNull @Size(min = 1, max = 200) List<@Valid @NotNull LabelItem> labels) {
    }

    public record Result(int updated, List<String> missing) {
    }
}
