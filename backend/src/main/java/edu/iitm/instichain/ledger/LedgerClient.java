package edu.iitm.instichain.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * The authoritative event ledger. {@code instichain.ledger} selects the implementation:
 * {@code fabric} (default) or {@code ethereum}. Failures are reported as
 * {@link edu.iitm.instichain.fabric.FabricUnavailableException}.
 */
public interface LedgerClient {
    /** Value stored in {@code fabric_transactions.source}. */
    String source();

    /** Channel name or chain identifier, stored in {@code fabric_transactions.channel_name}. */
    String network();

    /** Chaincode name or contract address, stored in {@code fabric_transactions.chaincode_name}. */
    String contract();

    /**
     * Appends a signed canonical event document and waits for it to be committed.
     * The returned event echoes the document plus {@code sequence},
     * {@code fabric_transaction_id}, {@code committed_at}, and {@code schema_version}.
     */
    Receipt submit(JsonNode document);

    JsonNode head();

    JsonNode events(int startSequence, int limit);

    /** Returns the event, or {@code null} when the ledger has no such request ID. */
    JsonNode event(UUID requestId);

    JsonNode revocations();

    record Receipt(JsonNode event, String submittedTransactionId,
                   long blockNumber, int validationCode) {}
}
