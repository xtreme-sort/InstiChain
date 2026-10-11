package edu.iitm.instichain.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.junit.jupiter.api.Test;

class Ed25519Test {
    @Test
    void derivesPublicKeyAndVerifiesSignatures() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] publicKey = Ed25519.rawPublic(pair.getPrivate());
        byte[] message = "signed institutional event".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] signature = Ed25519.sign(pair.getPrivate(), message);

        assertTrue(Ed25519.matches(pair.getPrivate(), publicKey));
        assertTrue(Ed25519.verify(publicKey, message, signature));
        assertFalse(Ed25519.verify(publicKey, "changed".getBytes(), signature));
    }
}
