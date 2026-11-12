package com.personal.finance.service;

import com.personal.finance.dto.TransactionView.Split;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes the compact text form of a split payment. */
final class Splits {

    private Splits() {
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
            out.append(p.category().replace('|', ' ').replace(';', ' ')).append('|').append(p.amount().toPlainString());
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
                int bar = piece.lastIndexOf('|');
                BigDecimal amount = new BigDecimal(piece.substring(bar + 1));
                parts.add(new Split(piece.substring(0, bar), amount));
                sum = sum.add(amount);
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return parts.size() >= 2 && sum.compareTo(total) == 0 ? List.copyOf(parts) : List.of();
    }
}
