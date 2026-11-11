package com.personal.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record StagedMessageItem(
        @NotNull Channel channel,
        @NotBlank @Size(max = 128) String externalId,
        @Size(max = 255) String sender,
        @Size(max = 500) String subject,
        @NotNull Instant receivedAt,
        @NotBlank @Size(max = 20000) String body,
        boolean bodyTruncated,
        @Size(max = 255) String labels) {

    public enum Channel {
        SMS,
        EMAIL,
        NOTIF
    }
}
