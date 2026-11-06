package kr.magi.mirror.common;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** A fresh in-memory identity per user-prepared session. APK signing keys are unrelated. */
public final class TlsIdentity implements AutoCloseable {
    public static final String SUITE = "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256";
    private KeyPair pair;
    private X509Certificate certificate;
    private boolean closed;

    public TlsIdentity() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        pair = generator.generateKeyPair();
        long now = System.currentTimeMillis();
        X500Name name = new X500Name("CN=GEUMBOK ephemeral synthetic session");
        BouncyCastleProvider provider = new BouncyCastleProvider();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name,
                new BigInteger(128, new SecureRandom()).add(BigInteger.ONE),
                new Date(now - 60000), new Date(now + 7200000), name, pair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        certificate = new JcaX509CertificateConverter().setProvider(provider).getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider(provider).build(pair.getPrivate())));
    }

    public synchronized String fingerprint() throws Exception {
        requireOpen();
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(pair.getPublic().getEncoded());
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02X", value & 255));
        return result.toString();
    }

    public synchronized SSLContext context(String peerFingerprint) throws Exception {
        requireOpen();
        byte[] pin = decodePin(peerFingerprint);
        KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
        keys.load(null);
        keys.setKeyEntry("session", pair.getPrivate(), new char[0], new java.security.cert.Certificate[]{certificate});
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, new char[0]);
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(managers.getKeyManagers(), new TrustManager[]{new PinnedTrust(pin)}, new SecureRandom());
        Arrays.fill(pin, (byte) 0);
        return context;
    }

    public static void configure(SSLSocket socket, boolean client) {
        socket.setUseClientMode(client);
        socket.setEnabledProtocols(new String[]{"TLSv1.2"});
        socket.setEnabledCipherSuites(new String[]{SUITE});
        socket.setEnableSessionCreation(true);
        if (!client) socket.setNeedClientAuth(true);
    }

    public static byte[] decodePin(String text) {
        String value = text.replace(" ", "").replace("-", "");
        if (!value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Use all 64 hexadecimal digits");
        byte[] pin = new byte[32];
        for (int i = 0; i < pin.length; i++) pin[i] = (byte) Integer.parseInt(value.substring(2*i, 2*i+2), 16);
        return pin;
    }

    private static final class PinnedTrust implements X509TrustManager {
        private final byte[] pin;
        PinnedTrust(byte[] pin) { this.pin = pin.clone(); }
        private boolean matchesPin(X509Certificate[] chain) throws CertificateException {
            try {
                if (chain == null || chain.length != 1) throw new CertificateException("Unexpected certificate chain");
                chain[0].checkValidity();
                chain[0].verify(chain[0].getPublicKey());
                byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getPublicKey().getEncoded());
                return MessageDigest.isEqual(pin, actual);
            } catch (CertificateException exception) { throw exception; }
            catch (Exception exception) { throw new CertificateException("Peer verification failed", exception); }
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (!matchesPin(chain)) throw new CertificateException("Client identity mismatch");
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (!matchesPin(chain)) throw new CertificateException("Server identity mismatch");
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("Session ended"); }
    @Override public synchronized void close() { closed = true; pair = null; certificate = null; }
}
