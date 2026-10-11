package edu.iitm.instichain.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class InstituteEmailTest {
    @Test
    void normalizesInstituteDomains() {
        assertEquals("user@iitm.ac.in", InstituteEmail.normalize(" User@IITM.AC.IN "));
        assertEquals("user@smail.iitm.ac.in", InstituteEmail.normalize("user@smail.iitm.ac.in"));
    }

    @Test
    void rejectsLookalikesAndMalformedAddresses() {
        for (String email : new String[]{"user@gmail.com", "user@eviliitm.ac.in",
                "user@iitm.ac.in.evil.com", "user@@iitm.ac.in", "a..b@iitm.ac.in",
                "user@iitm.ac.in\r\nBcc:x@example.com", "user@iit\u043c.ac.in"}) {
            assertThrows(IllegalArgumentException.class, () -> InstituteEmail.normalize(email));
        }
    }
}
