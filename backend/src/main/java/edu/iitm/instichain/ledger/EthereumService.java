package edu.iitm.instichain.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.iitm.instichain.common.CanonicalJson;
import edu.iitm.instichain.config.InstiChainProperties;
import edu.iitm.instichain.fabric.FabricUnavailableException;
import jakarta.annotation.PreDestroy;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes16;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.WalletUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;
import org.web3j.utils.Numeric;

/**
 * Ethereum implementation of the ledger, backed by {@code ethereum/contracts/InstiChainLedger.sol}.
 * Only a keccak256 hash of each canonical signed document goes on-chain; reads join the
 * on-chain entry with the PostgreSQL projection and report whether the hashes still match.
 */
@Service
@ConditionalOnProperty(prefix = "instichain", name = "ledger", havingValue = "ethereum")
public class EthereumService implements LedgerClient {
    private static final Logger LOG = LoggerFactory.getLogger(EthereumService.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Map<String, String> REVOCATION_SUBJECTS = Map.of(
            "ROLE_REVOKED", "appointment_id",
            "ACHIEVEMENT_REVOKED", "credential_id");
    private static final Map<String, String> ISSUED_SUBJECTS = Map.of(
            "ACHIEVEMENT_ISSUED", "credential_ids");
    private static final Event EVENT_APPENDED = new Event("EventAppended", Arrays.<TypeReference<?>>asList(
            new TypeReference<Bytes16>(true) {}, new TypeReference<Uint64>(true) {},
            new TypeReference<Bytes32>() {}, new TypeReference<Bytes32>() {},
            new TypeReference<Bytes16>() {}));

    private final InstiChainProperties properties;
    private final CanonicalJson json;
    private final JdbcTemplate jdbc;
    private Web3j web3;
    private Credentials credentials;
    private RawTransactionManager transactions;

    public EthereumService(InstiChainProperties properties, CanonicalJson json, JdbcTemplate jdbc) {
        this.properties = properties;
        this.json = json;
        this.jdbc = jdbc;
    }

    @Override
    public String source() { return "ethereum"; }

    @Override
    public String network() { return "ethereum:" + properties.ethereum().chainId(); }

    @Override
    public String contract() { return properties.ethereum().contractAddress(); }

    @Override
    public synchronized Receipt submit(JsonNode document) {
        try {
            connect();
            String requestId = document.path("request_id").asText();
            String eventType = document.path("event_type").asText();
            byte[] documentHash = Hash.sha3(json.bytes(document));
            requireChainMatchesProjection();
            Entry entry = entry(uuidBytes(requestId));
            if (entry == null) {
                String data = FunctionEncoder.encode(new Function("appendEvent", Arrays.<Type>asList(
                        new Bytes16(uuidBytes(requestId)), new Bytes32(eventTypeBytes(eventType)),
                        new Bytes32(documentHash),
                        new Bytes16(revokedSubject(eventType, document.path("object_ids"))),
                        new DynamicArray<>(Bytes16.class,
                                issuedSubjects(eventType, document.path("object_ids")))),
                        Collections.emptyList()));
                EthSendTransaction sent = transactions.sendTransaction(
                        web3.ethGasPrice().send().getGasPrice(), estimateGas(data), contract(),
                        data, BigInteger.ZERO);
                if (sent.hasError()) {
                    throw new FabricUnavailableException(
                            "Ethereum rejected the transaction: " + sent.getError().getMessage());
                }
                TransactionReceipt receipt = new PollingTransactionReceiptProcessor(web3, 1000, 60)
                        .waitForTransactionReceipt(sent.getTransactionHash());
                if (!receipt.isStatusOK()) {
                    throw new FabricUnavailableException("Ethereum reverted the transaction");
                }
                entry = entry(uuidBytes(requestId));
                if (entry == null) throw new FabricUnavailableException("Ethereum did not record the event");
            }
            if (!Arrays.equals(entry.documentHash(), documentHash)) {
                throw new FabricUnavailableException(
                        "request_id " + requestId + " already contains a different event");
            }
            String transactionHash = transactionHash(uuidBytes(requestId), entry.blockNumber());
            ObjectNode event = document.deepCopy();
            event.put("schema_version", 1);
            event.put("sequence", entry.sequence());
            event.put("fabric_transaction_id", transactionHash);
            event.put("committed_at", Instant.ofEpochSecond(entry.committedAt()).toString());
            event.put("submitting_account", credentials.getAddress());
            event.put("document_hash", Numeric.toHexString(documentHash));
            return new Receipt(event, transactionHash, entry.blockNumber(), 0);
        } catch (FabricUnavailableException error) {
            throw error;
        } catch (Exception error) {
            LOG.warn("Ethereum submission failed", error);
            throw new FabricUnavailableException("Ethereum did not confirm the transaction", error);
        }
    }

    @Override
    public JsonNode head() {
        try {
            connect();
            long sequence = ((Uint64) call("head", List.of(), List.of(new TypeReference<Uint64>() {}))
                    .getFirst()).getValue().longValue();
            ObjectNode head = NODES.objectNode();
            head.put("sequence", sequence);
            if (sequence > 0) head.put("request_id", uuidString(requestAt(sequence)));
            head.put("chain_id", properties.ethereum().chainId());
            head.put("contract", contract());
            return head;
        } catch (FabricUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable(error);
        }
    }

    @Override
    public JsonNode events(int startSequence, int limit) {
        try {
            long last = head().path("sequence").asLong();
            ArrayNode events = NODES.arrayNode();
            for (long sequence = startSequence; sequence <= last && sequence < startSequence + limit; sequence++) {
                byte[] requestId = requestAt(sequence);
                events.add(describe(requestId, entry(requestId)));
            }
            return events;
        } catch (FabricUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable(error);
        }
    }

    @Override
    public JsonNode event(UUID requestId) {
        try {
            connect();
            byte[] id = uuidBytes(requestId.toString());
            Entry entry = entry(id);
            return entry == null ? null : describe(id, entry);
        } catch (FabricUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable(error);
        }
    }

    @Override
    public JsonNode revocations() {
        try {
            connect();
            long count = ((Uint256) call("revokedSubjectCount", List.of(),
                    List.of(new TypeReference<Uint256>() {})).getFirst()).getValue().longValue();
            ArrayNode revocations = NODES.arrayNode();
            for (long index = 0; index < count; index++) {
                byte[] subject = ((Bytes16) call("revokedSubjectAt", List.of(new Uint256(index)),
                        List.of(new TypeReference<Bytes16>() {})).getFirst()).getValue();
                byte[] requestId = ((Bytes16) call("revocationOf", List.of(new Bytes16(subject)),
                        List.of(new TypeReference<Bytes16>() {})).getFirst()).getValue();
                Entry entry = entry(requestId);
                ObjectNode revocation = NODES.objectNode();
                revocation.put("subject_id", uuidString(subject));
                revocation.put("request_id", uuidString(requestId));
                if (entry != null) {
                    revocation.put("event_type", eventTypeString(entry.eventType()));
                    revocation.put("sequence", entry.sequence());
                    revocation.put("fabric_transaction_id", transactionHash(requestId, entry.blockNumber()));
                    revocation.put("revoked_at", Instant.ofEpochSecond(entry.committedAt()).toString());
                }
                revocations.add(revocation);
            }
            return revocations;
        } catch (FabricUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable(error);
        }
    }

    /** Joins an on-chain entry with the PostgreSQL copy of the document and checks its hash. */
    private ObjectNode describe(byte[] requestId, Entry entry) throws Exception {
        String id = uuidString(requestId);
        ObjectNode event = NODES.objectNode();
        event.put("schema_version", 1);
        event.put("sequence", entry.sequence());
        event.put("request_id", id);
        event.put("event_type", eventTypeString(entry.eventType()));
        event.put("block_number", entry.blockNumber());
        event.put("committed_at", Instant.ofEpochSecond(entry.committedAt()).toString());
        event.put("document_hash", Numeric.toHexString(entry.documentHash()));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT fabric_transaction_id, object_ids::text AS object_ids, payload::text AS payload,
                       actor_id, actor_key_id, signature
                FROM fabric_transactions WHERE request_id = ?
                """, UUID.fromString(id));
        if (rows.isEmpty()) {
            event.put("fabric_transaction_id", transactionHash(requestId, entry.blockNumber()));
            event.put("document_available", false);
            return event;
        }
        Map<String, Object> row = rows.getFirst();
        ObjectNode document = NODES.objectNode();
        document.put("request_id", id);
        document.put("event_type", event.path("event_type").asText());
        document.set("object_ids", json.parse(((String) row.get("object_ids")).getBytes(StandardCharsets.UTF_8)));
        document.set("payload", json.parse(((String) row.get("payload")).getBytes(StandardCharsets.UTF_8)));
        document.put("actor_id", row.get("actor_id").toString());
        document.put("actor_key_id", row.get("actor_key_id").toString());
        byte[] signature = (byte[]) row.get("signature");
        if (signature == null) document.putNull("signature");
        else document.put("signature", Base64.getEncoder().encodeToString(signature));
        boolean verified = Arrays.equals(Hash.sha3(json.bytes(document)), entry.documentHash());
        event.setAll(document);
        event.put("fabric_transaction_id", (String) row.get("fabric_transaction_id"));
        event.put("document_available", true);
        event.put("hash_verified", verified);
        return event;
    }

    private Entry entry(byte[] requestId) throws Exception {
        List<Type> values = call("getEntry", List.of(new Bytes16(requestId)), List.of(
                new TypeReference<Bool>() {}, new TypeReference<Uint64>() {},
                new TypeReference<Uint64>() {}, new TypeReference<Uint64>() {},
                new TypeReference<Bytes32>() {}, new TypeReference<Bytes32>() {}));
        if (!((Bool) values.get(0)).getValue()) return null;
        return new Entry(((Uint64) values.get(1)).getValue().longValue(),
                ((Uint64) values.get(2)).getValue().longValue(),
                ((Uint64) values.get(3)).getValue().longValue(),
                ((Bytes32) values.get(4)).getValue(), ((Bytes32) values.get(5)).getValue());
    }

    private byte[] requestAt(long sequence) throws Exception {
        return ((Bytes16) call("requestAt", List.of(new Uint64(sequence)),
                List.of(new TypeReference<Bytes16>() {})).getFirst()).getValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Type> call(String name, List<Type> inputs, List<TypeReference<?>> outputs) throws Exception {
        Function function = new Function(name, inputs, outputs);
        EthCall response = web3.ethCall(Transaction.createEthCallTransaction(
                credentials.getAddress(), contract(), FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST).send();
        if (response.hasError() || response.isReverted()) {
            throw new FabricUnavailableException("Ethereum call " + name + " failed");
        }
        return FunctionReturnDecoder.decode(response.getValue(), (List) function.getOutputParameters());
    }

    private BigInteger estimateGas(String data) throws Exception {
        EthEstimateGas estimate = web3.ethEstimateGas(Transaction.createFunctionCallTransaction(
                credentials.getAddress(), null, null, null, contract(), data)).send();
        if (estimate.hasError()) {
            throw new FabricUnavailableException(
                    "Ethereum rejected the event: " + estimate.getError().getMessage());
        }
        return estimate.getAmountUsed().multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
    }

    private String transactionHash(byte[] requestId, long blockNumber) throws Exception {
        DefaultBlockParameter block = DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber));
        EthFilter filter = new EthFilter(block, block, contract())
                .addSingleTopic(EventEncoder.encode(EVENT_APPENDED))
                .addSingleTopic(Numeric.toHexString(Arrays.copyOf(requestId, 32)));
        EthLog logs = web3.ethGetLogs(filter).send();
        if (logs.hasError() || logs.getLogs().isEmpty()) {
            throw new FabricUnavailableException("Ethereum has no log for the recorded event");
        }
        return ((Log) logs.getLogs().getFirst().get()).getTransactionHash();
    }

    private synchronized void connect() throws Exception {
        if (web3 != null) return;
        InstiChainProperties.Ethereum ethereum = properties.ethereum();
        if (ethereum.rpcUrl() == null || ethereum.rpcUrl().isBlank()) {
            throw new FabricUnavailableException("Configure INSTICHAIN_ETHEREUM_RPC_URL first");
        }
        if (ethereum.contractAddress() == null || !WalletUtils.isValidAddress(ethereum.contractAddress())) {
            throw new FabricUnavailableException("Configure INSTICHAIN_ETHEREUM_CONTRACT_ADDRESS first");
        }
        if (ethereum.privateKey() == null || ethereum.privateKey().isBlank()) {
            throw new FabricUnavailableException("Configure INSTICHAIN_ETHEREUM_PRIVATE_KEY first");
        }
        Web3j client = Web3j.build(new HttpService(ethereum.rpcUrl()));
        String code = client.ethGetCode(ethereum.contractAddress(), DefaultBlockParameterName.LATEST)
                .send().getCode();
        if (code == null || code.equals("0x")) {
            client.shutdown();
            throw new FabricUnavailableException("No InstiChainLedger contract at "
                    + ethereum.contractAddress() + "; run make ethereum-deploy");
        }
        credentials = Credentials.create(ethereum.privateKey());
        transactions = new RawTransactionManager(client, credentials, ethereum.chainId());
        web3 = client;
    }

    @PreDestroy
    void close() {
        if (web3 != null) web3.shutdown();
    }

    private static FabricUnavailableException unavailable(Exception error) {
        LOG.warn("Ethereum read failed", error);
        return new FabricUnavailableException("The Ethereum ledger is unavailable", error);
    }

    /**
     * A local Hardhat chain is lost when the node stops, but PostgreSQL keeps its receipts.
     * Writing to the fresh chain would silently fork history, so refuse instead: the latest
     * receipt recorded for this contract must still be on-chain at the same sequence.
     */
    private void requireChainMatchesProjection() throws Exception {
        if (jdbc == null) return;
        List<Map<String, Object>> latest = jdbc.queryForList("""
                SELECT request_id, sequence FROM fabric_transactions
                WHERE source = 'ethereum' AND chaincode_name = ? AND channel_name = ?
                ORDER BY sequence DESC LIMIT 1
                """, contract(), network());
        if (latest.isEmpty()) return;
        long sequence = ((Number) latest.getFirst().get("sequence")).longValue();
        String recorded = latest.getFirst().get("request_id").toString();
        long head = ((Uint64) call("head", List.of(), List.of(new TypeReference<Uint64>() {}))
                .getFirst()).getValue().longValue();
        if (head < sequence || !recorded.equals(uuidString(requestAt(sequence)))) {
            throw new FabricUnavailableException("The Ethereum chain at " + contract()
                    + " no longer contains event " + sequence + " recorded in PostgreSQL. The chain was"
                    + " probably reset; use a fresh database or restore the original chain.");
        }
    }

    private static byte[] revokedSubject(String eventType, JsonNode objectIds) {
        String field = REVOCATION_SUBJECTS.get(eventType);
        return field == null ? new byte[16] : uuidBytes(objectIds.path(field).asText());
    }

    static List<Bytes16> issuedSubjects(String eventType, JsonNode objectIds) {
        String field = ISSUED_SUBJECTS.get(eventType);
        if (field == null) return List.of();
        List<Bytes16> subjects = new java.util.ArrayList<>();
        objectIds.path(field).forEach(id -> subjects.add(new Bytes16(uuidBytes(id.asText()))));
        return subjects;
    }

    static byte[] uuidBytes(String value) {
        UUID uuid = UUID.fromString(value);
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits()).array();
    }

    static String uuidString(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    static byte[] eventTypeBytes(String eventType) {
        byte[] ascii = eventType.getBytes(StandardCharsets.US_ASCII);
        if (ascii.length == 0 || ascii.length > 32) {
            throw new FabricUnavailableException("Ethereum event types must be 1-32 characters");
        }
        return Arrays.copyOf(ascii, 32);
    }

    static String eventTypeString(byte[] value) {
        int length = 0;
        while (length < value.length && value[length] != 0) length++;
        return new String(value, 0, length, StandardCharsets.US_ASCII);
    }

    private record Entry(long sequence, long blockNumber, long committedAt,
                         byte[] eventType, byte[] documentHash) {}
}
