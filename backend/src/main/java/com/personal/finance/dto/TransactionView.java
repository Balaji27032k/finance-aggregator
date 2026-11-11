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
        List<Source> sources) {

    public record Source(String type, String bankCode) {
    }
}
