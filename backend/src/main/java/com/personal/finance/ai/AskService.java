package com.personal.finance.ai;

import com.personal.finance.service.TransactionQueryService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;

@Service
public class AskService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_DAYS = 730;
    private static final int MAX_GROUPS = 10_000;

    private final TransactionQueryService transactions;
    private final AskEngine engine;
    private final Clock clock;

    public AskService(TransactionQueryService transactions, AiModel model, Clock clock) {
        this.transactions = transactions;
        this.engine = new AskEngine(model, clock);
        this.clock = clock;
    }

    public AskDtos.Response ask(AskDtos.Request request) {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        var all = transactions.list(
                today.minusDays(MAX_DAYS + 2L).atStartOfDay(IST).toInstant(), today.plusDays(2).atStartOfDay(IST).toInstant(), MAX_GROUPS);
        return engine.ask(request, all);
    }
}
