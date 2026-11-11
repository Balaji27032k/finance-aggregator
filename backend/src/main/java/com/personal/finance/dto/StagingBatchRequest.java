package com.personal.finance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record StagingBatchRequest(@NotEmpty @Size(max = 100) List<@Valid StagedMessageItem> messages) {
}
