package com.personal.finance.service;

import com.personal.finance.dto.EventItem;
import com.personal.finance.dto.EventStatusResponse;
import com.personal.finance.dto.IngestResult;
import com.personal.finance.entity.FinancialTransaction;
import com.personal.finance.entity.OutboxEvent;
import com.personal.finance.entity.RawFinancialEvent;
import com.personal.finance.exception.ResourceNotFoundException;
import com.personal.finance.exception.ServiceUnavailableException;
import com.personal.finance.repository.FinancialTransactionRepository;
import com.personal.finance.repository.OutboxEventRepository;
import com.personal.finance.repository.RawFinancialEventRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EventIngestionService {

    private static final Duration MAX_FUTURE_SKEW = Duration.ofDays(1);

    private final RawFinancialEventRepository events;
    private final OutboxEventRepository outbox;
    private final FinancialTransactionRepository ledger;
    private final TransactionTemplate transactions;
    private final Validator validator;
    private final Clock clock;

    public EventIngestionService(RawFinancialEventRepository events, OutboxEventRepository outbox,
                                 FinancialTransactionRepository ledger, TransactionTemplate transactions,
                                 Validator validator, Clock clock) {
        this.events = events;
        this.outbox = outbox;
        this.ledger = ledger;
        this.transactions = transactions;
        this.validator = validator;
        this.clock = clock;
    }

    public List<IngestResult> ingestBatch(Long deviceId, List<EventItem> items) {
        List<IngestResult> results = new ArrayList<>(items.size());
        for (EventItem item : items) {
            results.add(ingest(deviceId, item));
        }
        return results;
    }

    /**
     * Each event commits in its own transaction together with its outbox row. An event is only reported ACCEPTED
     * or DUPLICATE once the row is durably stored; a database outage surfaces as {@link ServiceUnavailableException}.
     */
    public IngestResult ingest(Long deviceId, EventItem item) {
        if (item == null) {
            return IngestResult.rejected(null, "empty item");
        }
        String invalid = validate(item);
        if (invalid != null) {
            return IngestResult.rejected(item.localEventId(), invalid);
        }
        try {
            return transactions.execute(status -> store(deviceId, item));
        } catch (DataIntegrityViolationException race) {
            // Another request stored the same event between our check and insert.
            return lookupDuplicate(deviceId, item).orElseThrow(() -> race);
        } catch (DataAccessResourceFailureException | TransientDataAccessException | CannotCreateTransactionException e) {
            throw new ServiceUnavailableException(e);
        }
    }

    public EventStatusResponse status(Long deviceId, Long eventId) {
        return events.findStatusByIdAndDevice(eventId, deviceId)
                .map(v -> new EventStatusResponse(v.getId(), v.getLocalEventId(), v.getProcessingStatus(), v.getCreatedAt()))
                .orElseThrow(() -> new ResourceNotFoundException("Event"));
    }

    private IngestResult store(Long deviceId, EventItem item) {
        Optional<Long> existing = events.findIdBySource(deviceId, item.sourceType(), item.sourceEventId());
        if (existing.isPresent()) {
            return IngestResult.duplicate(item.localEventId(), existing.get());
        }
        Instant now = clock.instant();
        RawFinancialEvent saved = events.saveAndFlush(new RawFinancialEvent(
                deviceId, item.localEventId(), item.sourceType(), item.sourceApplication(), item.sourceSender(),
                item.sourceEventId(), item.eventTimestamp(), item.capturedTimestamp(), item.payloadHash(),
                item.parserStatus(), item.parserVersion(), now));
        ledger.save(new FinancialTransaction(saved.getId(), deviceId, item.transaction(), item.eventTimestamp(), now));
        outbox.save(OutboxEvent.rawEventReceived(saved.getId(), now));
        return IngestResult.accepted(item.localEventId(), saved.getId());
    }

    private Optional<IngestResult> lookupDuplicate(Long deviceId, EventItem item) {
        return events.findIdBySource(deviceId, item.sourceType(), item.sourceEventId())
                .map(id -> IngestResult.duplicate(item.localEventId(), id));
    }

    /** Returns a reason listing only field names, never values. */
    private String validate(EventItem item) {
        Set<ConstraintViolation<EventItem>> violations = validator.validate(item);
        if (!violations.isEmpty()) {
            Set<String> fields = new TreeSet<>();
            violations.forEach(v -> fields.add(v.getPropertyPath().toString()));
            return "invalid fields: " + String.join(", ", fields);
        }
        if (item.eventTimestamp().isAfter(clock.instant().plus(MAX_FUTURE_SKEW))) {
            return "eventTimestamp is in the future";
        }
        return null;
    }
}
