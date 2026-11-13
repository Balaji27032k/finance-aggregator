package com.personal.finance.service;

import com.personal.finance.entity.Direction;
import com.personal.finance.entity.FinancialTransaction;
import com.personal.finance.entity.SourceType;
import com.personal.finance.repository.FinancialTransactionRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Finds the earlier row, if any, that describes the same real transaction seen through another channel.
 * A shared reference number (UPI RRN, UTR, IMPS ref) is conclusive. Without one, a transaction of the same
 * amount and direction seen by a different channel within a few minutes, with a compatible counterparty, matches.
 */
@Component
public class TransactionMatcher {

    static final Duration WINDOW = Duration.ofMinutes(3);
    static final Duration STATEMENT_WINDOW = Duration.ofHours(36);
    private static final int MIN_REFERENCE_LENGTH = 9;

    public record Match(Long groupId, String basis) {
    }

    private final FinancialTransactionRepository ledger;

    public TransactionMatcher(FinancialTransactionRepository ledger) {
        this.ledger = ledger;
    }

    public Optional<Match> find(Direction direction, BigDecimal amount, String reference, SourceType source,
                                Instant at, String counterparty) {
        boolean hasReference = reference != null && reference.length() >= MIN_REFERENCE_LENGTH;
        if (hasReference) {
            Optional<FinancialTransaction> byReference = ledger
                    .findFirstByReferenceNoAndDirectionAndAmountOrderByIdAsc(reference, direction, amount);
            if (byReference.isPresent()) {
                return Optional.of(new Match(groupOf(byReference.get()), "REF"));
            }
        }
        Optional<Match> byTime = ledger.findNearbyFromOtherSources(direction, amount, source, at.minus(WINDOW), at.plus(WINDOW)).stream()
                .filter(c -> !hasReference || c.getReferenceNo() == null || c.getReferenceNo().equals(reference))
                .filter(c -> compatible(counterparty, c.getCounterparty()))
                .filter(c -> !ledger.existsByGroupIdAndSourceType(groupOf(c), source))
                .min(Comparator.comparing(c -> Duration.between(c.getEventTimestamp(), at).abs()))
                .map(c -> new Match(groupOf(c), "TIME"));
        return byTime.isPresent() ? byTime : matchStatement(direction, amount, reference, source, at, counterparty);
    }

    /**
     * A bank statement row has a date but no time, so it is matched to a payment-app notification of the same amount and direction
     * on the same or the next day (and the other way round), as long as the names do not clash.
     */
    private Optional<Match> matchStatement(Direction direction, BigDecimal amount, String reference, SourceType source, Instant at,
                                           String counterparty) {
        SourceType partner = source == SourceType.MANUAL ? SourceType.NOTIFICATION : source == SourceType.NOTIFICATION ? SourceType.MANUAL : null;
        if (partner == null) {
            return Optional.empty();
        }
        return ledger.findNearbyFromOtherSources(direction, amount, source, at.minus(STATEMENT_WINDOW), at.plus(STATEMENT_WINDOW)).stream()
                .filter(c -> c.getSourceType() == partner)
                .filter(c -> reference == null || c.getReferenceNo() == null || c.getReferenceNo().equals(reference))
                .filter(c -> compatible(counterparty, c.getCounterparty()))
                .filter(c -> !ledger.existsByGroupIdAndSourceType(groupOf(c), source))
                .min(Comparator.comparing(c -> Duration.between(c.getEventTimestamp(), at).abs()))
                .map(c -> new Match(groupOf(c), "STMT"));
    }

    private static Long groupOf(FinancialTransaction t) {
        return t.getGroupId() != null ? t.getGroupId() : t.getId();
    }

    /** Names match when either is unknown or they share a word (or the start of one). */
    public static boolean compatible(String a, String b) {
        if (a == null || b == null) {
            return true;
        }
        Set<String> left = words(a);
        Set<String> right = words(b);
        for (String x : left) {
            for (String y : right) {
                if (x.startsWith(y) || y.startsWith(x)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> words(String value) {
        Set<String> out = new HashSet<>();
        for (String w : value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 3) {
                out.add(w);
            }
        }
        return out;
    }
}
