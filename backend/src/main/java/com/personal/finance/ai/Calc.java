package com.personal.finance.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A calculation the model asks for; the arithmetic is done here, never by the model. */
record CalcRequest(String type, BigDecimal amount, BigDecimal rate, BigDecimal years) {
}

/** Standard savings and loan formulas. Rates are annual percentages. */
final class Calc {

    static final Set<String> TYPES = Set.of("sip_future", "sip_needed", "lump_future", "fd", "rd", "ppf", "emi", "inflate");

    private Calc() {
    }

    /** The answer with its inputs and a plain statement of the method, or null when the request is not sensible. */
    static Map<String, Object> run(CalcRequest c) {
        if (c == null || c.type() == null || !TYPES.contains(c.type()) || c.amount() == null || c.rate() == null || c.years() == null) {
            return null;
        }
        double amount = c.amount().doubleValue();
        double rate = c.rate().doubleValue();
        double years = c.years().doubleValue();
        if (amount <= 0 || amount > 1e11 || rate < 0 || rate > 40 || years <= 0 || years > 60) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", c.type());
        out.put("amount", round(amount));
        out.put("annualRatePercent", c.rate().stripTrailingZeros());
        out.put("years", c.years().stripTrailingZeros());
        double months = years * 12;
        switch (c.type()) {
            case "sip_future" -> {
                double fv = annuityDue(amount, rate / 1200, months);
                out.put("method", "monthly SIP at the start of each month, compounded monthly");
                out.put("invested", round(amount * months));
                out.put("futureValue", round(fv));
                out.put("gains", round(fv - amount * months));
            }
            case "sip_needed" -> {
                double perRupee = annuityDue(1, rate / 1200, months);
                double sip = amount / perRupee;
                out.put("method", "monthly SIP at the start of each month, compounded monthly, to reach the goal amount");
                out.put("monthlySip", round(sip));
                out.put("invested", round(sip * months));
            }
            case "lump_future" -> {
                out.put("method", "one-time amount compounded yearly");
                out.put("futureValue", round(amount * Math.pow(1 + rate / 100, years)));
            }
            case "fd" -> {
                double fv = amount * Math.pow(1 + rate / 400, 4 * years);
                out.put("method", "fixed deposit compounded quarterly");
                out.put("maturityValue", round(fv));
                out.put("interest", round(fv - amount));
            }
            case "rd" -> {
                int n = (int) Math.round(months);
                double fv = 0;
                for (int k = 1; k <= n; k++) {
                    fv += amount * Math.pow(1 + rate / 400, 4 * (n - k + 1) / 12.0);
                }
                out.put("method", "recurring deposit, monthly deposits, interest compounded quarterly");
                out.put("invested", round(amount * n));
                out.put("maturityValue", round(fv));
            }
            case "ppf" -> {
                int n = (int) Math.round(years);
                double fv = 0;
                for (int k = 1; k <= n; k++) {
                    fv += amount * Math.pow(1 + rate / 100, n - k + 1);
                }
                out.put("method", "yearly deposit made early in each year (full-year interest), compounded yearly");
                out.put("invested", round(amount * n));
                out.put("maturityValue", round(fv));
            }
            case "emi" -> {
                double r = rate / 1200;
                int n = (int) Math.round(months);
                double emi = r == 0 ? amount / n : amount * r * Math.pow(1 + r, n) / (Math.pow(1 + r, n) - 1);
                out.put("method", "loan amount repaid in equal monthly instalments");
                out.put("monthlyEmi", round(emi));
                out.put("totalInterest", round(emi * n - amount));
            }
            default -> {
                out.put("method", "cost today grown by inflation each year");
                out.put("futureCost", round(amount * Math.pow(1 + rate / 100, years)));
            }
        }
        return out;
    }

    /** Future value of one rupee-per-month deposits made at the start of each month. */
    private static double annuityDue(double monthly, double r, double n) {
        if (r == 0) {
            return monthly * n;
        }
        return monthly * ((Math.pow(1 + r, n) - 1) / r) * (1 + r);
    }

    private static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP);
    }
}
