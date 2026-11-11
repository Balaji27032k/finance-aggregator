package com.personal.finance.entity;

import com.personal.finance.dto.TransactionItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "financial_transactions")
public class FinancialTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "raw_event_id", nullable = false, updatable = false)
    private Long rawEventId;

    @Column(name = "device_id", nullable = false, updatable = false)
    private Long deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Direction direction;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "account_last4", updatable = false)
    private String accountLast4;

    @Column(updatable = false)
    private String counterparty;

    @Column(name = "counterparty_account_last4", updatable = false)
    private String counterpartyAccountLast4;

    @Column(name = "payment_mode", updatable = false)
    private String paymentMode;

    @Column(name = "reference_no", updatable = false)
    private String referenceNo;

    @Column(name = "balance_after", updatable = false)
    private BigDecimal balanceAfter;

    @Column(name = "transaction_date", updatable = false)
    private LocalDate transactionDate;

    @Column(name = "bank_code", nullable = false, updatable = false)
    private String bankCode;

    @Column(updatable = false)
    private String note;

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FinancialTransaction() {
    }

    public FinancialTransaction(Long rawEventId, Long deviceId, TransactionItem t, Instant eventTimestamp, Instant now) {
        this.rawEventId = rawEventId;
        this.deviceId = deviceId;
        this.direction = t.direction();
        this.amount = t.amount();
        this.currency = t.currency().toUpperCase();
        this.accountLast4 = t.accountLast4();
        this.counterparty = t.counterparty();
        this.counterpartyAccountLast4 = t.counterpartyAccountLast4();
        this.paymentMode = t.paymentMode();
        this.referenceNo = t.referenceNo();
        this.balanceAfter = t.balanceAfter();
        this.transactionDate = t.transactionDate();
        this.bankCode = t.bankCode();
        this.note = t.note();
        this.eventTimestamp = eventTimestamp;
        this.createdAt = now;
    }

    public Long getId() {
        return id;
    }
}
