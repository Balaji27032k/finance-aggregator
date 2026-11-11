package com.personal.finance.dto;

import com.personal.finance.entity.ParserStatus;
import com.personal.finance.entity.SourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record EventItem(
        @NotBlank @Size(max = 64) String localEventId,
        @NotNull SourceType sourceType,
        @NotBlank @Size(max = 128) String sourceApplication,
        @Size(max = 128) String sourceSender,
        @NotBlank @Size(max = 128) String sourceEventId,
        @NotNull Instant eventTimestamp,
        @NotNull Instant capturedTimestamp,
        @NotBlank @Size(min = 64, max = 64) String payloadHash,
        @NotNull @Valid TransactionItem transaction,
        @NotNull ParserStatus parserStatus,
        @NotBlank @Size(max = 32) String parserVersion) {
}
