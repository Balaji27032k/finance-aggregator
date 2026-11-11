package com.personal.finance.demo;

import com.personal.finance.common.Hashing;
import com.personal.finance.dto.EventItem;
import com.personal.finance.dto.RegisterRequest;
import com.personal.finance.dto.TransactionItem;
import com.personal.finance.entity.Direction;
import com.personal.finance.entity.ParserStatus;
import com.personal.finance.entity.SourceType;
import com.personal.finance.service.DeviceService;
import com.personal.finance.service.EventIngestionService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Only with the "demo" profile (in-memory database): fills it with made-up transactions so the dashboard can be tried
 * without any real data. Never active in production.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String[] FOOD = {"SWIGGY", "ZOMATO", "Blue Tokai Coffee", "Murugan Idli Shop", "Hungerbox", "Anjappar Restaurant", "Cafe Coffee Day"};
    private static final String[] RIDE = {"UBER INDIA", "RAPIDO", "Chennai Metro Rail", "IRCTC", "HP Petrol Pump"};
    private static final String[] SHOP = {"AMAZON PAY", "Flipkart", "MYNTRA", "DMart Avenue", "Reliance Smart", "Decathlon"};
    private static final String[] FRIENDS = {"Arjun K", "Priya S", "Karthik R", "Divya M"};

    private final DeviceService devices;
    private final EventIngestionService ingestion;
    private final Clock clock;
    private final Random random = new Random(42);
    private final List<EventItem> events = new ArrayList<>();
    private BigDecimal balance = new BigDecimal("48000.00");
    private int counter;

    public DemoDataSeeder(DeviceService devices, EventIngestionService ingestion, Clock clock) {
        this.devices = devices;
        this.ingestion = ingestion;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long deviceId = devices.register(new RegisterRequest("demo-pairing-code", "Demo phone", "demo")).deviceId();
        LocalDate today = LocalDate.now(clock.withZone(IST));
        for (LocalDate day = today.minusDays(200); day.isBefore(today); day = day.plusDays(1)) {
            simulate(day);
        }
        int accepted = 0;
        for (int i = 0; i < events.size(); i += 40) {
            accepted += (int) ingestion.ingestBatch(deviceId, events.subList(i, Math.min(i + 40, events.size()))).stream()
                    .filter(r -> r.status().name().equals("ACCEPTED")).count();
        }
        log.info("Demo data ready: {} made-up messages loaded", accepted);
    }

    private void simulate(LocalDate day) {
        int dom = day.getDayOfMonth();
        boolean weekend = day.getDayOfWeek().getValue() >= 6;
        if (dom == 1) {
            add(day, Direction.CREDIT, "88500.00", "ACME TECHNOLOGIES", "NEFT", "Salary", "HDFCBK", true);
        }
        if (dom == 3) {
            add(day, Direction.DEBIT, "24000.00", "Landlord Rao", "UPI", "House rent", "HDFCBK", false);
        }
        if (dom == 5) {
            add(day, Direction.DEBIT, "5000.00", "GROWW MUTUAL FUND", "UPI", "SIP", "HDFCBK", false);
        }
        if (dom == 8) {
            add(day, Direction.DEBIT, "999.00", "ACT FIBERNET", "UPI", "Broadband", "HDFCBK", false);
        }
        if (dom == 11) {
            add(day, Direction.DEBIT, "649.00", "NETFLIX", "UPI", "Subscription", "HDFCBK", false);
        }
        if (dom == 14) {
            add(day, Direction.DEBIT, money(1400, 2600), "TANGEDCO", "UPI", "Electricity bill", "HDFCBK", false);
        }
        if (dom == 20) {
            add(day, Direction.DEBIT, "299.00", "JIO PREPAID", "UPI", "Recharge", "HDFCBK", false);
        }
        int meals = weekend ? 2 : 1 + random.nextInt(2);
        for (int i = 0; i < meals; i++) {
            add(day, Direction.DEBIT, money(90, weekend ? 1100 : 520), pick(FOOD), "UPI", null, "SBIUPI", random.nextInt(8) == 0);
        }
        if (!weekend && random.nextInt(3) > 0) {
            add(day, Direction.DEBIT, money(35, 420), pick(RIDE), "UPI", null, "SBIUPI", false);
        }
        if (random.nextInt(9) == 0) {
            add(day, Direction.DEBIT, money(300, 4800), pick(SHOP), "CARD", null, "HDFCBK", true);
        }
        if (random.nextInt(25) == 0) {
            add(day, Direction.DEBIT, money(180, 1400), "Apollo Pharmacy", "UPI", "Medicines", "SBIUPI", false);
        }
        if (random.nextInt(20) == 0) {
            add(day, Direction.DEBIT, money(2000, 6000), "ATM WDL", "ATM", "Cash", "HDFCBK", false);
        }
        if (random.nextInt(12) == 0) {
            add(day, Direction.CREDIT, money(300, 3000), pick(FRIENDS), "UPI", "Dinner split", "SBIUPI", false);
        }
        if (random.nextInt(14) == 0) {
            add(day, Direction.DEBIT, money(200, 2500), pick(FRIENDS), "UPI", "Sent", "SBIUPI", false);
        }
    }

    private void add(LocalDate day, Direction direction, String amount, String counterparty, String mode, String note,
                     String bank, boolean alsoEmail) {
        BigDecimal value = new BigDecimal(amount);
        balance = direction == Direction.CREDIT ? balance.add(value) : balance.subtract(value);
        if (balance.signum() < 0) {
            balance = balance.add(new BigDecimal("30000.00"));
        }
        Instant at = day.atTime(8 + random.nextInt(14), random.nextInt(60)).atZone(IST).toInstant();
        String ref = String.format("%012d", 400000000000L + counter * 7919L);
        events.add(event(SourceType.SMS, bank, at, direction, value, counterparty, mode, note, ref, day));
        if (alsoEmail) {
            events.add(event(SourceType.GMAIL, bank, at.plusSeconds(40), direction, value, counterparty, mode, note, ref, day));
        }
    }

    private EventItem event(SourceType source, String bank, Instant at, Direction direction, BigDecimal amount,
                            String counterparty, String mode, String note, String ref, LocalDate day) {
        counter++;
        String id = "demo-" + counter;
        TransactionItem t = new TransactionItem(direction, amount, "INR", "4821", counterparty, null, mode, ref,
                balance, day, bank, note);
        return new EventItem(id, source, source == SourceType.GMAIL ? "gmail" : bank, null, id, at, at.plusSeconds(2),
                Hashing.sha256Hex(id), t, ParserStatus.PARSED, "demo-1");
    }

    private String money(int min, int max) {
        return BigDecimal.valueOf(min + random.nextInt(max - min + 1)).setScale(2).toPlainString();
    }

    private String pick(String[] options) {
        return options[random.nextInt(options.length)];
    }
}
