package edu.iitm.instichain.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;

public final class Ed25519 {
    private static final byte[] PUBLIC_PREFIX = java.util.HexFormat.of()
            .parseHex("302a300506032b6570032100");

    private Ed25519() {}

    public static PrivateKey readPrivateKey(Path path) throws IOException, GeneralSecurityException {
        if (path == null || path.toString().isBlank()) {
            throw new IOException("Configure INSTICHAIN_ADMIN_SIGNING_KEY_FILE first.");
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                throw new IOException("The signing key must be readable only by its owner (chmod 600).");
            }
        } catch (UnsupportedOperationException ignored) {
            // POSIX permissions are unavailable on this filesystem.
        }
        String pem = Files.readString(path);
        String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }

    public static PublicKey publicKey(byte[] raw) throws GeneralSecurityException {
        if (raw == null || raw.length != 32) throw new GeneralSecurityException("Invalid Ed25519 public key");
        byte[] encoded = new byte[PUBLIC_PREFIX.length + raw.length];
        System.arraycopy(PUBLIC_PREFIX, 0, encoded, 0, PUBLIC_PREFIX.length);
        System.arraycopy(raw, 0, encoded, PUBLIC_PREFIX.length, raw.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }

    public static byte[] rawPublic(PrivateKey privateKey) throws GeneralSecurityException {
        try {
            PrivateKeyInfo info = PrivateKeyInfo.getInstance(privateKey.getEncoded());
            byte[] seed = ASN1OctetString.getInstance(info.parsePrivateKey()).getOctets();
            if (seed.length == 34 && seed[0] == 0x04 && seed[1] == 0x20) {
                seed = java.util.Arrays.copyOfRange(seed, 2, seed.length);
            }
            return new Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().getEncoded();
        } catch (Exception error) {
            throw new GeneralSecurityException("Could not derive the Ed25519 public key", error);
        }
    }

    public static byte[] sign(PrivateKey key, byte[] payload) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key);
        signature.update(payload);
        return signature.sign();
    }

    public static boolean verify(byte[] publicKey, byte[] payload, byte[] signed) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey(publicKey));
            verifier.update(payload);
            return verifier.verify(signed);
        } catch (GeneralSecurityException error) {
            return false;
        }
    }

    public static boolean matches(PrivateKey privateKey, byte[] publicKey) {
        try {
            byte[] challenge = "instichain-key-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return verify(publicKey, challenge, sign(privateKey, challenge));
        } catch (GeneralSecurityException error) {
            return false;
        }
    }
}
