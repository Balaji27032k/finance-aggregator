package com.personal.finance.tools;

import java.io.Console;
import java.util.Scanner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Prints a bcrypt hash for the dashboard password (the value for ADMIN_PASSWORD_HASH). The password is typed at a
 * prompt (or piped in with --stdin), never given as an argument, so it does not end up in shell history.
 */
public final class HashPassword {

    private HashPassword() {
    }

    public static void main(String[] args) {
        Console console = System.console();
        String password;
        if (console != null && !(args.length > 0 && "--stdin".equals(args[0]))) {
            char[] typed = console.readPassword("Dashboard password: ");
            password = typed == null ? "" : new String(typed);
        } else {
            password = new Scanner(System.in).nextLine();
        }
        if (password.length() < 12) {
            System.err.println("Use at least 12 characters.");
            System.exit(1);
        }
        System.out.println(new BCryptPasswordEncoder(12).encode(password));
    }
}
