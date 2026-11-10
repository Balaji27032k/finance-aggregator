package com.personal.finance.dto;

public record IngestResult(String localEventId, Status status, Long serverEventId, String reason) {

    public enum Status { ACCEPTED, DUPLICATE, REJECTED }

    public static IngestResult accepted(String localEventId, Long serverEventId) {
        return new IngestResult(localEventId, Status.ACCEPTED, serverEventId, null);
    }

    public static IngestResult duplicate(String localEventId, Long serverEventId) {
        return new IngestResult(localEventId, Status.DUPLICATE, serverEventId, null);
    }

    public static IngestResult rejected(String localEventId, String reason) {
        return new IngestResult(localEventId, Status.REJECTED, null, reason);
    }
}
