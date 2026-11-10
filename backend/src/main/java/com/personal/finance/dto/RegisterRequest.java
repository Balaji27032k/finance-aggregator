package com.personal.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 128) String pairingCode,
        @NotBlank @Size(max = 100) String deviceName,
        @Size(max = 32) String appVersion) {
}
