package com.personal.finance.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class AskDtos {

    private AskDtos() {
    }

    public record Turn(@NotBlank @Size(max = 10) String role, @NotNull @Size(max = 600) String text) {
    }

    public record Request(@NotBlank @Size(max = 300) String question, @Size(max = 12) List<@Valid @NotNull Turn> history) {
    }

    public record Totals(BigDecimal total, int count) {
    }

    /** A name and what it adds up to: a related item, a category or a payee. */
    public record Group(String name, BigDecimal total, int count) {
    }

    public record Item(String name, String title, LocalDate date, BigDecimal amount, boolean related) {
    }

    /**
     * [sentToAi] is exactly what left the server for the model, so the user can check it. [exact] and [related] are
     * only filled for questions about a particular thing.
     */
    public record Response(
            String answer,
            boolean aiUsed,
            String intent,
            String periodLabel,
            LocalDate from,
            LocalDate to,
            List<String> terms,
            Totals exact,
            Totals related,
            String breakdownTitle,
            List<Group> breakdown,
            List<Item> items,
            List<String> suggestions,
            List<String> sentToAi,
            String note) {
    }
}
