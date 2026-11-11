package com.personal.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AdminViews() {

    public record TransactionPage(int total, int page, int size, BigDecimal totalDebit, BigDecimal totalCredit,
                                  List<String> categories, List<TransactionView> items) {
    }

    public record Count(String key, long count, Instant latest) {
    }

    public record DataHealth(long transactions, long multiChannelTransactions, Instant firstEvent, List<Count> bySource,
                             List<Count> byBank, Instant serverTime) {
    }
}
