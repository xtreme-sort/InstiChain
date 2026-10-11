package edu.iitm.instichain.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import edu.iitm.instichain.common.CanonicalJson;
import edu.iitm.instichain.config.InstiChainProperties;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.Proposal;
import org.hyperledger.fabric.client.Status;
import org.hyperledger.fabric.client.SubmittedTransaction;
import org.hyperledger.fabric.client.Transaction;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Identity;
import org.hyperledger.fabric.client.identity.Signer;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FabricService {
    private static final Logger LOG = LoggerFactory.getLogger(FabricService.class);
    private final InstiChainProperties properties;
    private final CanonicalJson json;
    private ManagedChannel channel;
    private Gateway gateway;
    private Contract contract;

    public FabricService(InstiChainProperties properties, CanonicalJson json) {
        this.properties = properties;
        this.json = json;
    }

    public synchronized Receipt submit(JsonNode document) {
        try {
            Proposal proposal = contract().newProposal("AppendEvent")
                    .addArguments(json.string(document)).build();
            Transaction transaction = proposal.endorse();
            SubmittedTransaction submitted = transaction.submitAsync();
            Status status = submitted.getStatus(call -> call.withDeadlineAfter(60, TimeUnit.SECONDS));
            if (!status.isSuccessful()) {
                throw new FabricUnavailableException("Fabric rejected the transaction");
            }
            JsonNode event = json.parse(submitted.getResult());
            return new Receipt(event, status.getTransactionId(), status.getBlockNumber(),
                    status.getCode().getNumber());
        } catch (FabricUnavailableException error) {
            throw error;
        } catch (Exception error) {
            LOG.warn("Fabric submission failed", error);
            throw new FabricUnavailableException("Fabric did not confirm the transaction", error);
        }
    }

    public JsonNode evaluate(String transaction, String... arguments) {
        try {
            return json.parse(contract().evaluateTransaction(transaction, arguments));
        } catch (Exception error) {
            LOG.warn("Fabric evaluation {} failed", transaction, error);
            throw new FabricUnavailableException("The Fabric ledger is unavailable", error);
        }
    }

    private Contract contract() throws IOException, CertificateException, InvalidKeyException {
        if (contract != null) return contract;
        InstiChainProperties.Fabric fabric = properties.fabric();
        Path crypto = required(fabric.cryptoPath(), "INSTICHAIN_FABRIC_CRYPTO_PATH");
        Path keyDirectory = configured(fabric.keyDirectory())
                ? fabric.keyDirectory() : crypto.resolve("users/User1@org1.example.com/msp/keystore");
        Path certDirectory = configured(fabric.certDirectory())
                ? fabric.certDirectory() : crypto.resolve("users/User1@org1.example.com/msp/signcerts");
        Path tlsCertificate = configured(fabric.tlsCertPath())
                ? fabric.tlsCertPath() : crypto.resolve("peers/peer0.org1.example.com/tls/ca.crt");

        ChannelCredentials tls = TlsChannelCredentials.newBuilder()
                .trustManager(tlsCertificate.toFile()).build();
        channel = (ManagedChannel) Grpc.newChannelBuilder(fabric.peerEndpoint(), tls)
                .overrideAuthority(fabric.peerHostAlias()).build();
        X509Certificate certificate = Identities.readX509Certificate(
                new StringReader(Files.readString(firstFile(certDirectory))));
        PrivateKey privateKey = Identities.readPrivateKey(
                new StringReader(Files.readString(firstFile(keyDirectory))));
        Identity identity = new X509Identity(fabric.mspId(), certificate);
        Signer signer = Signers.newPrivateKeySigner(privateKey);
        gateway = Gateway.newInstance().identity(identity).signer(signer).connection(channel)
                .evaluateOptions(call -> call.withDeadlineAfter(5, TimeUnit.SECONDS))
                .endorseOptions(call -> call.withDeadlineAfter(15, TimeUnit.SECONDS))
                .submitOptions(call -> call.withDeadlineAfter(5, TimeUnit.SECONDS))
                .commitStatusOptions(call -> call.withDeadlineAfter(60, TimeUnit.SECONDS))
                .connect();
        contract = gateway.getNetwork(fabric.channelName()).getContract(fabric.chaincodeName());
        return contract;
    }

    private static Path firstFile(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).findFirst()
                    .orElseThrow(() -> new IOException("No identity file found in " + directory));
        }
    }

    private static Path required(Path path, String setting) {
        if (!configured(path)) throw new FabricUnavailableException("Configure " + setting + " first");
        return path;
    }

    private static boolean configured(Path path) {
        return path != null && !path.toString().isBlank();
    }

    @PreDestroy
    void close() {
        if (gateway != null) gateway.close();
        if (channel != null) channel.shutdownNow();
    }

    public record Receipt(JsonNode event, String submittedTransactionId,
                          long blockNumber, int validationCode) {}
}
