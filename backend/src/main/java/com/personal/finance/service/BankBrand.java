package com.personal.finance.service;

import java.util.Locale;

/** One bank under every name its messages use (SBIUPI, CBSSBI, ATMSBI and the e-mail alerts are all SBI). */
final class BankBrand {

    private BankBrand() {
    }

    static String of(String code) {
        if (code == null || code.isBlank()) {
            return "Unknown bank";
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        if (c.startsWith("SBI") || c.equals("CBSSBI") || c.equals("ATMSBI") || c.equals("SBYONO") || c.contains("STATE BANK")) {
            return "SBI";
        }
        if (c.startsWith("CSB") || c.contains("CATHOLIC SYRIAN")) {
            return "CSB Bank";
        }
        if (c.equals("INDBNK") || c.startsWith("INDIAN BANK")) {
            return "Indian Bank";
        }
        if (c.startsWith("ICICI")) {
            return "ICICI";
        }
        return code.trim();
    }
}
