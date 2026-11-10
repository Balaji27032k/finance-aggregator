package com.personal.finance.dto;

import java.time.Instant;

public record HeartbeatResponse(Instant serverTime) {
}
