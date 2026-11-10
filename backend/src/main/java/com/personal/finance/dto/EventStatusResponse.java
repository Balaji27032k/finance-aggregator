package com.personal.finance.dto;

import com.personal.finance.entity.ProcessingStatus;
import java.time.Instant;

public record EventStatusResponse(Long serverEventId, String localEventId, ProcessingStatus processingStatus,
                                  Instant receivedAt) {
}
