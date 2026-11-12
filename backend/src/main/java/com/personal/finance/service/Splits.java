package com.personal.finance.service;

import com.personal.finance.dto.TransactionView.Split;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes the compact text form of a split payment: "Category|Name|amount;Category|Name|amount". */
final class Splits {

    private Splits() {
    }

    private static String plain(String value) {
        return value == null ? "" : value.replace('|', ' ').replace(';', ' ');
    }

    static String encode(List<Split> parts) {
        if (parts == null || parts.size() < 2) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (Split p : parts) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(plain(p.category())).append('|').append(plain(p.name())).append('|').append(p.amount().toPlainString());
        }
        return out.toString();
    }

    /** Empty when the text is missing, unreadable or its parts do not add up to the payment. */
    static List<Split> decode(String text, BigDecimal total) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Split> parts = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        try {
            for (String piece : text.split(";")) {
                // Two fields (Category|amount) is the earlier form without a name.
                String[] f = piece.split("\\|", -1);
                BigDecimal amount = new BigDecimal(f[f.length - 1]);
                String name = f.length >= 3 && !f[1].isBlank() ? f[1] : null;
                parts.add(new Split(f[0], name, amount));
                sum = sum.add(amount);
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return parts.size() >= 2 && sum.compareTo(total) == 0 ? List.copyOf(parts) : List.of();
    }
}
