package com.personal.finance.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CalcTest {

    private static Map<String, Object> run(String type, String amount, String rate, String years) {
        return Calc.run(new CalcRequest(type, new BigDecimal(amount), new BigDecimal(rate), new BigDecimal(years)));
    }

    private static long value(Map<String, Object> result, String key) {
        return ((BigDecimal) result.get(key)).longValue();
    }

    @Test
    void aMonthlySipGrowsLikeTheStandardFormula() {
        Map<String, Object> r = run("sip_future", "10000", "12", "10");
        assertEquals(1_200_000, value(r, "invested"));
        assertEquals(2_323_391, value(r, "futureValue"));
        assertEquals(1_123_391, value(r, "gains"));
    }

    @Test
    void theSipNeededForAGoalReachesThatGoal() {
        long sip = value(run("sip_needed", "2323391", "12", "10"), "monthlySip");
        assertTrue(Math.abs(sip - 10_000) <= 1, "sip " + sip);
    }

    @Test
    void anEmiMatchesTheUsualLoanTable() {
        Map<String, Object> r = run("emi", "1000000", "8.5", "20");
        assertEquals(8_678, value(r, "monthlyEmi"));
        assertTrue(value(r, "totalInterest") > 1_000_000);
    }

    @Test
    void depositsCompoundAsBanksDo() {
        long fd = value(run("fd", "100000", "7", "5"), "maturityValue");
        assertTrue(Math.abs(fd - 141_478) <= 3, "fd " + fd);
        long ppf = value(run("ppf", "150000", "7.1", "15"), "maturityValue");
        assertTrue(ppf > 4_000_000 && ppf < 4_300_000, "ppf " + ppf);
        long rd = value(run("rd", "5000", "6.5", "5"), "maturityValue");
        assertTrue(rd > 300_000 && rd < 360_000, "rd " + rd);
        assertEquals(1_790_848, value(run("lump_future", "1000000", "6", "10"), "futureValue"));
        assertEquals(1_790_848, value(run("inflate", "1000000", "6", "10"), "futureCost"));
    }

    @Test
    void nonsenseRequestsAreRefused() {
        assertNull(run("sip_future", "-5", "12", "10"));
        assertNull(run("sip_future", "1000", "90", "10"));
        assertNull(run("sip_future", "1000", "12", "100"));
        assertNull(run("magic", "1000", "12", "10"));
        assertNull(Calc.run(new CalcRequest("emi", null, BigDecimal.ONE, BigDecimal.ONE)));
        assertNotNull(run("emi", "500000", "0", "5"));
    }
}
