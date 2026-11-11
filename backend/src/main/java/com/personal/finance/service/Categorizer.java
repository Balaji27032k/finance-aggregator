package com.personal.finance.service;

import com.personal.finance.entity.Direction;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Display-only labels derived from the payee and note as they were received; nothing is stored or renamed. */
public final class Categorizer {

    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    static {
        RULES.put("Investments", p("groww|zerodha|angel|indmoney|upstox|mutual fund|\\bsip\\b|\\bnse\\b|\\bbse\\b|axis mf|nippon"));
        RULES.put("Rent & housing", p("\\brent\\b|landlord|housing|apartment|society|maintenance|\\bpg\\b"));
        RULES.put("Bills & recharge", p("jio|airtel|vodafone|\\bvi\\b|bsnl|recharge|electricity|tneb|tangedco|broadband|\\bdth\\b|postpaid|prepaid|"
                + "insurance|uiic|\\blic\\b|\\bgas\\b|water board|autopay|mandate|sms charge"));
        RULES.put("Groceries", p("bigbasket|blinkit|zepto|instamart|grocer|kirana|vegetable|\\bfruits?\\b|\\bmilk\\b|dairy|provision|supermarket|dmart|reliance fresh|\\bmore retail"));
        RULES.put("Transport", p("transport|metro|\\bbus\\b|railway|irctc|redbus|rapido|uber|\\bola\\b|cumta|setc|tnstc|petrol|fuel|toll|fastag|"
                + "parking|\\bcab\\b|auto fare|traffic"));
        RULES.put("Food & drinks", p("hungerbox|swiggy|zomato|restaurant|hotel|cafe|coffee|\\btea\\b|bakery|biryani|\\bfood|eatgood|eats|"
                + "kitchen|canteen|sweets|juice|pizza|burger|meal|mess\\b|dosa|idly|snack|ice cream|bhojan|foods|catering"));
        RULES.put("Health", p("pharma|medical|medi\\b|hospital|clinic|\\blab\\b|apollo|health|diagnostic|dental"));
        RULES.put("Shopping", p("amazon|flipkart|myntra|ajio|meesho|\\bmart\\b|supermarket|\\bstores?\\b|wholesale|fashion|\\bmall\\b|bazaar|retail|"
                + "jewel|textile|trends|max "));
        RULES.put("Entertainment & apps", p("google|spotify|netflix|prime video|hotstar|youtube|bookmyshow|play store|gpay-tv|apple|subscription"));
    }

    private Categorizer() {
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    public static String categorize(Direction direction, String counterparty, String paymentMode, String note) {
        String text = ((counterparty == null ? "" : counterparty) + " " + (note == null ? "" : note)).toLowerCase(Locale.ROOT);
        if (direction == Direction.CREDIT && text.contains("salary")) {
            return "Salary";
        }
        if ("ATM".equalsIgnoreCase(paymentMode)) {
            return direction == Direction.DEBIT ? "Cash withdrawal" : "Cash deposit";
        }
        for (Map.Entry<String, Pattern> rule : RULES.entrySet()) {
            if (rule.getValue().matcher(text).find()) {
                return rule.getKey();
            }
        }
        if (text.contains("interest")) {
            return "Interest";
        }
        return direction == Direction.CREDIT ? "Money received" : "Other";
    }
}
