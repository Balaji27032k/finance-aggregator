package com.personal.finance.service;

import com.personal.finance.dto.TransactionView;
import com.personal.finance.entity.FinancialTransaction;
import com.personal.finance.entity.SourceType;
import com.personal.finance.repository.FinancialTransactionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Reads transactions as the user sees them: one entry per real transaction, details merged across channels. */
@Service
public class TransactionQueryService {

    private final FinancialTransactionRepository ledger;

    public TransactionQueryService(FinancialTransactionRepository ledger) {
        this.ledger = ledger;
    }

    public List<TransactionView> list(Instant from, Instant to, int limit) {
        List<Long> ids = ledger.findGroupIds(from, to, PageRequest.of(0, limit));
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, List<FinancialTransaction>> byGroup = new LinkedHashMap<>();
        for (FinancialTransaction t : ledger.findByGroupIdIn(ids)) {
            byGroup.computeIfAbsent(t.getGroupId(), k -> new ArrayList<>()).add(t);
        }
        return ids.stream().map(byGroup::get).filter(rows -> rows != null).map(TransactionQueryService::merge).toList();
    }

    /** E-mail first (it carries the payee and your remark), then SMS, then notifications. Values are used as received. */
    static TransactionView merge(List<FinancialTransaction> rows) {
        List<FinancialTransaction> ordered = rows.stream()
                .sorted(Comparator.comparingInt((FinancialTransaction t) -> rank(t.getSourceType())).thenComparing(FinancialTransaction::getId))
                .toList();
        FinancialTransaction first = ordered.get(0);
        Instant earliest = rows.stream().map(FinancialTransaction::getEventTimestamp).min(Comparator.naturalOrder()).orElseThrow();
        List<TransactionView.Source> sources = ordered.stream()
                .map(t -> new TransactionView.Source(t.getSourceType() == null ? "UNKNOWN" : t.getSourceType().name(), t.getBankCode()))
                .toList();
        String counterparty = pick(ordered, FinancialTransaction::getCounterparty);
        String mode = pick(ordered, FinancialTransaction::getPaymentMode);
        String note = pick(ordered, FinancialTransaction::getNote);
        return new TransactionView(
                first.getGroupId(), first.getDirection(), first.getAmount(), first.getCurrency(),
                pick(ordered, FinancialTransaction::getTransactionDate), earliest,
                counterparty, pick(ordered, FinancialTransaction::getCounterpartyAccountLast4),
                pick(ordered, FinancialTransaction::getAccountLast4), mode,
                pick(ordered, FinancialTransaction::getReferenceNo), pick(ordered, FinancialTransaction::getBalanceAfter),
                note, Categorizer.categorize(first.getDirection(), counterparty, mode, note), sources);
    }

    private static int rank(SourceType type) {
        if (type == SourceType.GMAIL) {
            return 0;
        }
        return type == SourceType.SMS ? 1 : 2;
    }

    private static <T> T pick(List<FinancialTransaction> ordered, Function<FinancialTransaction, T> field) {
        for (FinancialTransaction t : ordered) {
            T value = field.apply(t);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
