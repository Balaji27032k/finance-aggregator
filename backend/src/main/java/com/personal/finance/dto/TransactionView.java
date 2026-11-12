package com.personal.finance.dto;

import com.personal.finance.entity.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** One real transaction, merged from every channel that reported it. */
public record TransactionView(
        Long groupId,
        Direction direction,
        BigDecimal amount,
        String currency,
        LocalDate transactionDate,
        Instant eventTimestamp,
        String counterparty,
        String counterpartyAccountLast4,
        String accountLast4,
        String paymentMode,
        String referenceNo,
        BigDecimal balanceAfter,
        String note,
        String category,
        String label,
        List<Source> sources,
        List<Split> splits) {

    public record Source(String type, String bankCode) {
    }

    /** The user's own split of a payment; [name] is what it was, such as "Masala dosa" (may be null). The parts add up to the amount. */
    public record Split(String category, String name, BigDecimal amount) {
    }

    /** The category and amount pairs this payment counts as in category totals. */
    public List<Split> partsByCategory() {
        return splits.isEmpty() ? List.of(new Split(category, null, amount)) : splits;
    }
}
