package com.personal.finance.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Removes anything that could identify an account before text leaves the server, and small text helpers for matching. */
final class Scrub {

    private static final Pattern ID_OR_VPA = Pattern.compile("[\\w.\\-]+@[\\w.\\-]+");
    private static final Pattern REFERENCE = Pattern.compile("\\b(?=[A-Za-z0-9]*\\d)(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{9,}\\b");
    private static final Pattern DIGITS = Pattern.compile("\\d(?:[\\s-]?\\d){3,}");
    private static final Pattern CARD_STYLE = Pattern.compile("(?i)\\b(?:x{2,}|\\*{2,})\\s*\\d+");

    private Scrub() {
    }

    /** Emails and UPI ids, reference numbers, runs of four or more digits and masked account numbers become placeholders. */
    static String text(String value, int max) {
        if (value == null) {
            return "";
        }
        String out = value.replaceAll("\\p{Cntrl}", " ");
        out = ID_OR_VPA.matcher(out).replaceAll("[id]");
        out = CARD_STYLE.matcher(out).replaceAll("[acct]");
        out = REFERENCE.matcher(out).replaceAll("[ref]");
        out = DIGITS.matcher(out).replaceAll("#");
        out = out.replaceAll("\\s+", " ").trim();
        return out.length() > max ? out.substring(0, max) : out;
    }

    /** True when nothing that looks like an identifier is left. */
    static boolean clean(String value) {
        return value != null && !ID_OR_VPA.matcher(value).find() && !DIGITS.matcher(value).find() && !REFERENCE.matcher(value).find();
    }

    static String norm(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    static List<String> tokens(String norm) {
        List<String> out = new ArrayList<>();
        for (String t : norm.split(" ")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Same word, a plural or a one or two letter typo. */
    static boolean near(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (a.length() < 4 || b.length() < 4) {
            return false;
        }
        if ((a.startsWith(b) || b.startsWith(a)) && Math.abs(a.length() - b.length()) <= 3) {
            return true;
        }
        int allowed = Math.min(a.length(), b.length()) >= 8 ? 2 : 1;
        return distance(a, b) <= allowed;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }
}
