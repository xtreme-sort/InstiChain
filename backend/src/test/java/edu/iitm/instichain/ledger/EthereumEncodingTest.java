package edu.iitm.instichain.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.iitm.instichain.fabric.FabricUnavailableException;
import org.junit.jupiter.api.Test;

class EthereumEncodingTest {
    @Test
    void roundTripsUuidsAsBytes16() {
        String id = "b5411aa1-6c8b-4ee9-8905-a3ee2081fc4f";
        byte[] encoded = EthereumService.uuidBytes(id);

        assertEquals(16, encoded.length);
        assertEquals((byte) 0xb5, encoded[0]);
        assertEquals(id, EthereumService.uuidString(encoded));
    }

    @Test
    void roundTripsEventTypesAsLeftAlignedBytes32() {
        byte[] encoded = EthereumService.eventTypeBytes("ROLE_REVOKED");

        assertEquals(32, encoded.length);
        assertEquals('R', encoded[0]);
        assertEquals(0, encoded[31]);
        assertEquals("ROLE_REVOKED", EthereumService.eventTypeString(encoded));
    }

    @Test
    void rejectsEventTypesThatDoNotFitBytes32() {
        assertThrows(FabricUnavailableException.class,
                () -> EthereumService.eventTypeBytes("A".repeat(33)));
    }
}
