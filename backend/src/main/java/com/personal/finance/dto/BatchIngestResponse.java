package com.personal.finance.dto;

import java.util.List;

public record BatchIngestResponse(List<IngestResult> results) {
}
