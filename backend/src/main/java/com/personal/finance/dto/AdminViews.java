package com.personal.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AdminViews() {

    public record TransactionPage(int total, int page, int size, BigDecimal totalDebit, BigDecimal totalCredit,
                                  List<String> categories, List<TransactionView> items) {
    }

    public record Count(String key, long count, Instant latest) {
    }

    /** What matched a free-text question such as "tea": [matched] is the part of each payment that belongs to it. */
    public record SpendLookup(String term, LocalDate from, LocalDate to, BigDecimal total, int count, List<Match> items) {

        public record Match(TransactionView transaction, BigDecimal matched) {
        }
    }

    public record DataHealth(long transactions, long multiChannelTransactions, Instant firstEvent, List<Count> bySource,
                             List<Count> byBank, Instant serverTime) {
    }
}
