package com.limelight.nvstream.http;

import android.util.Log;

import org.xmlpull.v1.XmlPullParserException;

import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.io.*;
import java.security.*;
import java.security.cert.*;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class PairingManager {
    private static final String TAG = "PairingManager";

    private static final int SHA256_HASH_LENGTH = 32;

    private final NvHTTP http;

    private final PrivateKey pk;
    private final X509Certificate cert;
    private final byte[] pemCertBytes;

    private X509Certificate serverCert;

    public enum PairState {
        NOT_PAIRED,
        PAIRED,
        PIN_WRONG,
        FAILED,
        ALREADY_IN_PROGRESS
    }

    public PairingManager(NvHTTP http, LimelightCryptoProvider cryptoProvider) {
        this.http = http;
        this.cert = cryptoProvider.getClientCertificate();
        this.pemCertBytes = cryptoProvider.getPemEncodedClientCertificate();
        this.pk = cryptoProvider.getClientPrivateKey();
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    private static byte[] hexToBytes(String s) {
        int len = s.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Illegal string length: " + len);
        }

        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private X509Certificate extractPlainCert(String text) throws XmlPullParserException, IOException {
        // Plaincert may be null if another client is already trying to pair
        String certText = NvHTTP.getXmlString(text, "plaincert", false);
        if (certText != null) {
            byte[] certBytes = hexToBytes(certText);

            try {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
            } catch (CertificateException e) {
                Log.e(TAG, "Failed to parse server certificate: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        } else {
            return null;
        }
    }

    private byte[] generateRandomBytes() {
        byte[] rand = new byte[16];
        new SecureRandom().nextBytes(rand);
        return rand;
    }

    private static byte[] saltPin(byte[] salt, String pin) {
        byte[] saltedPin = new byte[salt.length + pin.length()];
        System.arraycopy(salt, 0, saltedPin, 0, salt.length);
        System.arraycopy(pin.getBytes(StandardCharsets.UTF_8), 0, saltedPin, salt.length, pin.length());
        return saltedPin;
    }

    private static Signature getSha256SignatureInstanceForKey(Key key) throws NoSuchAlgorithmException {
        switch (key.getAlgorithm()) {
            case "RSA":
                return Signature.getInstance("SHA256withRSA");
            case "EC":
                return Signature.getInstance("SHA256withECDSA");
            default:
                throw new NoSuchAlgorithmException("Unhandled key algorithm: " + key.getAlgorithm());
        }
    }

    private static boolean verifySignature(byte[] data, byte[] signature, Certificate cert) {
        try {
            Signature sig = getSha256SignatureInstanceForKey(cert.getPublicKey());
            sig.initVerify(cert.getPublicKey());
            sig.update(data);
            return sig.verify(signature);
        } catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException e) {
            Log.e(TAG, "verifySignature: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private static byte[] signData(byte[] data, PrivateKey key) {
        try {
            Signature sig = getSha256SignatureInstanceForKey(key);
            sig.initSign(key);
            sig.update(data);
            return sig.sign();
        } catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException e) {
            Log.e(TAG, "signData: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Compute SHA-256 hash of data
     */
    private static byte[] sha256Hash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "SHA-256 not available: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    // ECB mode is required by the Sunshine/GameStream protocol
    @SuppressWarnings("InsecureCryptoUsage")
    private static byte[] decryptAes(byte[] encryptedData, byte[] aesKey) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);

            // Pad input to block size if necessary
            int blockSize = cipher.getBlockSize();
            int blockRoundedSize = (encryptedData.length + (blockSize - 1)) & -blockSize;
            byte[] paddedInput = Arrays.copyOf(encryptedData, blockRoundedSize);

            return cipher.doFinal(paddedInput);
        } catch (Exception e) {
            Log.e(TAG, "decryptAes failed: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    // ECB mode is required by the Sunshine/GameStream protocol
    @SuppressWarnings("InsecureCryptoUsage")
    private static byte[] encryptAes(byte[] plaintextData, byte[] aesKey) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);

            // Pad input to block size if necessary
            int blockSize = cipher.getBlockSize();
            int blockRoundedSize = (plaintextData.length + (blockSize - 1)) & -blockSize;
            byte[] paddedInput = Arrays.copyOf(plaintextData, blockRoundedSize);

            return cipher.doFinal(paddedInput);
        } catch (Exception e) {
            Log.e(TAG, "encryptAes failed: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private static byte[] generateAesKey(byte[] keyData) {
        return Arrays.copyOf(sha256Hash(keyData), 16);
    }

    private static byte[] concatBytes(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    public static String generatePinString() {
        SecureRandom r = new SecureRandom();
        //noinspection DataFlowIssue
        return String.format((Locale) null, "%d%d%d%d",
                r.nextInt(10), r.nextInt(10),
                r.nextInt(10), r.nextInt(10));
    }

    public X509Certificate getPairedCert() {
        return serverCert;
    }

    public PairState pair(String pin) throws IOException, XmlPullParserException {
        // Generate a salt for hashing the PIN
        byte[] salt = generateRandomBytes();

        // Combine the salt and pin, then create an AES key from them (using SHA-256)
        byte[] aesKey = generateAesKey(saltPin(salt, pin));

        // Send the salt and get the server cert. This doesn't have a read timeout
        // because the user must enter the PIN before the server responds
        String getCert = http.executePairingCommand("phrase=getservercert&salt=" +
                        bytesToHex(salt) + "&clientcert=" + bytesToHex(pemCertBytes),
                false);
        if (!NvHTTP.getXmlString(getCert, "paired", true).equals("1")) {
            return PairState.FAILED;
        }

        // Save this cert for retrieval later
        serverCert = extractPlainCert(getCert);
        if (serverCert == null) {
            // Attempting to pair while another device is pairing will cause the server
            // to give an empty cert in the response.
            http.unpair();
            return PairState.ALREADY_IN_PROGRESS;
        }

        // Require this cert for TLS to this host
        http.setServerCert(serverCert);

        // Generate a random challenge and encrypt it with our AES key
        byte[] randomChallenge = generateRandomBytes();
        byte[] encryptedChallenge = encryptAes(randomChallenge, aesKey);

        // Send the encrypted challenge to the server
        String challengeResp = http.executePairingCommand("clientchallenge=" + bytesToHex(encryptedChallenge), true);
        if (!NvHTTP.getXmlString(challengeResp, "paired", true).equals("1")) {
            http.unpair();
            return PairState.FAILED;
        }

        // Decode the server's response and subsequent challenge
        byte[] encServerChallengeResponse = hexToBytes(NvHTTP.getXmlString(challengeResp, "challengeresponse", true));
        byte[] decServerChallengeResponse = decryptAes(encServerChallengeResponse, aesKey);

        byte[] serverResponse = Arrays.copyOfRange(decServerChallengeResponse, 0, SHA256_HASH_LENGTH);
        byte[] serverChallenge = Arrays.copyOfRange(decServerChallengeResponse, SHA256_HASH_LENGTH, SHA256_HASH_LENGTH + 16);

        // Using another 16 bytes secret, compute a challenge response hash using the secret, our cert sig, and the challenge
        byte[] clientSecret = generateRandomBytes();
        byte[] challengeRespHash = sha256Hash(concatBytes(concatBytes(serverChallenge, cert.getSignature()), clientSecret));
        byte[] challengeRespEncrypted = encryptAes(challengeRespHash, aesKey);
        String secretResp = http.executePairingCommand("serverchallengeresp=" + bytesToHex(challengeRespEncrypted), true);
        if (!NvHTTP.getXmlString(secretResp, "paired", true).equals("1")) {
            http.unpair();
            return PairState.FAILED;
        }

        // Get the server's signed secret
        byte[] serverSecretResp = hexToBytes(NvHTTP.getXmlString(secretResp, "pairingsecret", true));
        byte[] serverSecret = Arrays.copyOfRange(serverSecretResp, 0, 16);
        byte[] serverSignature = Arrays.copyOfRange(serverSecretResp, 16, serverSecretResp.length);

        // Ensure the authenticity of the data
        if (!verifySignature(serverSecret, serverSignature, serverCert)) {
            // Cancel the pairing process
            http.unpair();

            // Looks like a MITM
            return PairState.FAILED;
        }

        // Ensure the server challenge matched what we expected (aka the PIN was correct)
        byte[] serverChallengeRespHash = sha256Hash(concatBytes(concatBytes(randomChallenge, serverCert.getSignature()), serverSecret));
        if (!Arrays.equals(serverChallengeRespHash, serverResponse)) {
            // Cancel the pairing process
            http.unpair();

            // Probably got the wrong PIN
            return PairState.PIN_WRONG;
        }

        // Send the server our signed secret
        byte[] clientPairingSecret = concatBytes(clientSecret, signData(clientSecret, pk));
        String clientSecretResp = http.executePairingCommand("clientpairingsecret=" + bytesToHex(clientPairingSecret), true);
        if (!NvHTTP.getXmlString(clientSecretResp, "paired", true).equals("1")) {
            http.unpair();
            return PairState.FAILED;
        }

        // Do the initial challenge (seems necessary for us to show as paired)
        String pairChallenge = http.executePairingChallenge();
        if (!NvHTTP.getXmlString(pairChallenge, "paired", true).equals("1")) {
            http.unpair();
            return PairState.FAILED;
        }

        return PairState.PAIRED;
    }
}
