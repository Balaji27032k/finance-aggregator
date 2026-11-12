package com.personal.finance.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.finance.ai.AskDtos.Request;
import com.personal.finance.ai.AskDtos.Response;
import com.personal.finance.dto.TransactionView;
import com.personal.finance.dto.TransactionView.Split;
import com.personal.finance.entity.Direction;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AskEngineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.parse("2026-11-12");

    private static TransactionView txn(long id, String date, String amount, String payee, String category, String label, String note, List<Split> splits) {
        LocalDate d = LocalDate.parse(date);
        return new TransactionView(id, Direction.DEBIT, new BigDecimal(amount), "INR", d, d.atTime(12, 0).toInstant(ZoneOffset.UTC), payee, null,
                "5867", "UPI", "REF123456789012", new BigDecimal("98765.00"), note, category, label, List.of(), splits);
    }

    private static Split part(String name, String amount) {
        return new Split("Food & drinks", name, new BigDecimal(amount));
    }

    private static AskEngine engine(AiModel model) {
        return new AskEngine(model, CLOCK);
    }

    private static AiModel off() {
        return new AiModel() {
            public boolean configured() {
                return false;
            }

            public Optional<String> generate(String system, String user, boolean json) {
                throw new AssertionError("the model must not be called");
            }
        };
    }

    private static List<TransactionView> sample() {
        return List.of(
                txn(1, "2026-11-03", "200", "Hotel Saravana", "Food & drinks", null, null, List.of(part("Masala dosa", "150"), part("Tea", "50"))),
                txn(2, "2026-10-20", "90", "Murugan Dosa Corner", "Food & drinks", null, null, List.of()),
                txn(3, "2026-09-10", "100", "Hotel Saravana", "Food & drinks", null, null, List.of(part("Plain dosa", "60"), part("Coffee", "40"))),
                txn(4, "2026-08-15", "120", "Anand", "Food & drinks", "dossa", null, List.of()),
                txn(5, "2026-11-01", "500", "Big Bazaar", "Groceries", null, null, List.of()));
    }

    @Test
    void exactAndRelatedAreAddedUpSeparatelyWithoutTheAi() {
        Response r = engine(off()).ask(new Request("how much did I spend on dosa in the last 12 months", null), sample());
        assertEquals("spend_on", r.intent());
        assertFalse(r.aiUsed());
        // Masala dosa, the dosa corner and plain dosa; tea and the payee of a split payment do not count.
        assertEquals(new BigDecimal("300"), r.exact().total());
        assertEquals(3, r.exact().count());
        // A near spelling is shown as related, not mixed into the exact total.
        assertEquals(new BigDecimal("120"), r.related().total());
        assertTrue(r.answer().contains("\u20b9300"));
    }

    @Test
    void aLongerPhraseStillBringsTheCloserNamesInAsRelated() {
        Response r = engine(off()).ask(new Request("masala dosa spending this year", null), sample());
        assertEquals(new BigDecimal("150"), r.exact().total());
        // The dosa corner and plain dosa are the closer names, shown apart from the exact total.
        assertEquals(new BigDecimal("270"), r.related().total());
    }

    @Test
    void nothingFoundSaysSoInsteadOfGuessing() {
        Response r = engine(off()).ask(new Request("how much on pizza", null), sample());
        assertEquals(BigDecimal.ZERO, r.exact().total());
        assertTrue(r.answer().contains("could not find"));
    }

    @Test
    void summaryAndBiggestComeFromTheData() {
        Response s = engine(off()).ask(new Request("give me a summary this month", null), sample());
        assertEquals("summary", s.intent());
        assertTrue(s.answer().contains("\u20b9700"));
        Response b = engine(off()).ask(new Request("what were my biggest expenses in the last 12 months", null), sample());
        assertEquals("biggest", b.intent());
        assertEquals(new BigDecimal("500"), b.items().get(0).amount());
    }

    @Test
    void nothingThatIdentifiesAnAccountIsSentToTheModel() {
        List<String> seen = new ArrayList<>();
        AiModel recorder = new AiModel() {
            public boolean configured() {
                return true;
            }

            public Optional<String> generate(String system, String user, boolean json) {
                seen.add(user);
                return json
                        ? Optional.of("{\"intent\":\"spend_on\",\"terms\":[\"tea\"],\"related\":[],\"from\":\"2026-01-01\",\"to\":\"2026-11-12\","
                        + "\"periodLabel\":\"this year\",\"suggestions\":[\"And last month?\"]}")
                        : Optional.of("You spent a little on tea.");
            }
        };
        List<TransactionView> data = List.of(
                txn(1, "2026-11-03", "777", "RAJU.K@ybl", "Food & drinks", "tea 5867", "paid 9876543210 ref UPI123456789012", List.of()),
                txn(2, "2026-11-04", "888", "SBI A/c XX5867 Murugan", "Food & drinks", null, "to 4111 1111 1111 1111", List.of()));
        Response r = engine(recorder).ask(new Request("tea for 9876543210 in account XX5867?", null), data);
        assertTrue(r.aiUsed());
        assertEquals(2, seen.size());
        for (String prompt : seen) {
            assertFalse(prompt.contains("5867"), prompt);
            assertFalse(prompt.contains("9876543210"), prompt);
            assertFalse(prompt.contains("123456789012"), prompt);
            assertFalse(prompt.contains("4111"), prompt);
            assertFalse(prompt.contains("@"), prompt);
            assertFalse(prompt.contains("98765"), "balances are never sent");
        }
        // The question is planned without any amounts; only the finished totals go back for wording.
        assertFalse(seen.get(0).contains("777"));
        assertTrue(seen.get(1).contains("777"));
        assertEquals(seen, r.sentToAi());
        assertEquals("You spent a little on tea.", r.answer());
    }

    @Test
    void relatedNamesChosenByTheModelAreUsedAndInventedOnesAreIgnored() {
        JsonMapper mapper = JsonMapper.builder().build();
        BiFunction<String, Boolean, Optional<String>> reply = (user, json) -> {
            if (!json) {
                return Optional.of("ok");
            }
            int idliId = -1;
            for (JsonNode n : mapper.readTree(user).path("names")) {
                if ("Idli".equals(n.path("name").asString(""))) {
                    idliId = n.path("id").asInt(-1);
                }
            }
            return Optional.of("{\"intent\":\"spend_on\",\"terms\":[\"dosa\"],\"related\":[" + idliId + ",9999],\"from\":\"2026-01-01\","
                    + "\"to\":\"2026-11-12\",\"periodLabel\":\"this year\"}");
        };
        AiModel model = new AiModel() {
            public boolean configured() {
                return true;
            }

            public Optional<String> generate(String system, String user, boolean json) {
                return reply.apply(user, json);
            }
        };
        List<TransactionView> data = new ArrayList<>(sample());
        data.add(txn(6, "2026-11-05", "40", "Hotel Saravana", "Food & drinks", null, null, List.of(part("Idli", "25"), part("Water", "15"))));
        Response r = engine(model).ask(new Request("how much on dosa", null), data);
        assertEquals(new BigDecimal("25"), r.related().total().subtract(new BigDecimal("120")));
    }

    @Test
    void whenTheModelFailsTheBuiltInSearchAnswersAndSaysSo() {
        AiModel broken = new AiModel() {
            public boolean configured() {
                return true;
            }

            public Optional<String> generate(String system, String user, boolean json) {
                return Optional.empty();
            }
        };
        Response r = engine(broken).ask(new Request("how much on dosa", null), sample());
        assertFalse(r.aiUsed());
        assertEquals(new BigDecimal("300"), r.exact().total());
        assertTrue(r.note() != null && r.note().contains("built-in"));
    }

    @Test
    void aGarbledModelReplyFallsBackToo() {
        AiModel garbled = new AiModel() {
            public boolean configured() {
                return true;
            }

            public Optional<String> generate(String system, String user, boolean json) {
                return Optional.of("sure! here you go");
            }
        };
        Response r = engine(garbled).ask(new Request("how much on dosa", null), sample());
        assertFalse(r.aiUsed());
        assertEquals(new BigDecimal("300"), r.exact().total());
    }

    @Test
    void periodsAreReadFromTheQuestion() {
        assertEquals(LocalDate.parse("2026-10-01"), AskEngine.parsePeriod("spend last month", TODAY).orElseThrow().from());
        assertEquals(LocalDate.parse("2026-10-31"), AskEngine.parsePeriod("spend last month", TODAY).orElseThrow().to());
        assertEquals(LocalDate.parse("2026-03-01"), AskEngine.parsePeriod("what about march", TODAY).orElseThrow().from());
        assertEquals(LocalDate.parse("2025-12-01"), AskEngine.parsePeriod("in december", TODAY).orElseThrow().from());
        assertEquals(LocalDate.parse("2025-01-01"), AskEngine.parsePeriod("last year", TODAY).orElseThrow().from());
        assertEquals(LocalDate.parse("2025-11-12"), AskEngine.parsePeriod("across 1 year", TODAY).orElseThrow().from());
        assertEquals(LocalDate.parse("2026-10-22"), AskEngine.parsePeriod("last 3 weeks", TODAY).orElseThrow().from());
        assertTrue(AskEngine.parsePeriod("how much on tea", TODAY).isEmpty());
    }

    @Test
    void identifiersAreScrubbed() {
        assertEquals("pay [id] now", Scrub.text("pay raju.k@ybl now", 60));
        assertFalse(Scrub.text("acct 1234 5678 9012", 60).matches(".*\\d{4}.*"));
        assertTrue(Scrub.clean(Scrub.text("UPI/DR/412345678901/ABC", 60)));
        assertEquals("Masala dosa", Scrub.text("Masala dosa", 60));
    }
}
