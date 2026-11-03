package edu.iitm.instichain.sharing;

import edu.iitm.instichain.common.Hashing;
import java.security.SecureRandom;
import java.util.Base64;

public final class ShareToken {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String value;

    private ShareToken(String value) { this.value = value; }

    public static ShareToken issue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new ShareToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    public String value() { return value; }
    public String hash() { return Hashing.sha256(value); }
    @Override public String toString() { return "ShareToken[REDACTED]"; }
}
