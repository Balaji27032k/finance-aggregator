package com.personal.finance.dto;

import com.personal.finance.entity.Direction;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** The facts extracted from one bank message on the phone. The message text itself is never sent. */
public record TransactionItem(
        @NotNull Direction direction,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @Size(max = 8) String accountLast4,
        @Size(max = 200) String counterparty,
        @Size(max = 8) String counterpartyAccountLast4,
        @Size(max = 16) String paymentMode,
        @Size(max = 64) String referenceNo,
        @Digits(integer = 13, fraction = 2) BigDecimal balanceAfter,
        LocalDate transactionDate,
        @NotBlank @Size(max = 32) String bankCode,
        @Size(max = 255) String note) {
}
