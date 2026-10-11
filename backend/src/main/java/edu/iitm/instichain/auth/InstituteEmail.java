package edu.iitm.instichain.auth;

import java.util.Locale;
import java.util.regex.Pattern;

public final class InstituteEmail {
    private static final Pattern ADDRESS = Pattern.compile(
            "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@"
            + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    private InstituteEmail() {}

    public static String normalize(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Enter a valid institute email address");
        }
        String value = input.strip();
        if (value.length() > 254 || !value.chars().allMatch(character -> character < 128)
                || !ADDRESS.matcher(value).matches()) {
            throw new IllegalArgumentException("Enter a valid institute email address");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        String domain = normalized.substring(normalized.lastIndexOf('@') + 1);
        if (!domain.equals("iitm.ac.in") && !domain.endsWith(".iitm.ac.in")) {
            throw new IllegalArgumentException("Use an iitm.ac.in email address or one of its subdomains");
        }
        return normalized;
    }
}
