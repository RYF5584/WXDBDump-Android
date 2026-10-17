package io.github.ryf5584.wxdbdump.payload.proto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.github.ryf5584.wxdbdump.payload.Digest;

/**
 * Loopback handshake.
 *
 * <p>Two independent directions run over one connection:
 * <pre>
 *   app    -&gt; HELLO {v:1, client:"1.0.0", nonce:"&lt;app nonce&gt;"}
 *   module -&gt; ACK   {v:1, proof:"hmac(TOKEN, app nonce)", nonce:"&lt;module nonce&gt;"}
 *   app    -&gt; AUTH  {v:1, proof:"hmac(TOKEN, module nonce)"}
 *   module -&gt; READY {"ok":true}
 * </pre>
 *
 * <p>The ACK proves to the app that the module is present and holds the token, so the app
 * never has to read LSPosed's module list. The AUTH does the same in reverse: the module
 * refuses commands until the peer has proven it also holds the token, so a client that
 * simply opens the port and sends {@code DUMP} gets nothing.
 *
 * <h3>What this does not cover</h3>
 *
 * <p>TOKEN is a build-time constant that ships in the APK and in this repository, so AUTH
 * only filters out peers that have not read the source. It does not authenticate the app:
 * a malicious app on the same device that knows the token can still complete the
 * handshake. Closing that gap needs a per-install secret, and app and module run under
 * different uids with no shared writable location, so it needs a pairing step that does
 * not exist yet. See SECURITY.md.
 *
 * <p>Loopback TCP rather than a LocalSocket because cross-uid LocalSocket connections are
 * restricted by SELinux, while loopback TCP is unaffected by uid or scoped storage.
 */
public final class Handshake {

    public static final int PROTOCOL_VERSION = 1;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Handshake() {
    }

    /** Client greeting, carrying a fresh nonce the module must sign. */
    public static String hello(String clientVersion, String nonceHex) {
        return "HELLO {\"v\":" + PROTOCOL_VERSION
                + ",\"client\":\"" + clientVersion + "\""
                + ",\"nonce\":\"" + nonceHex + "\"}";
    }

    /** Module reply: signs the client nonce and supplies one of its own. */
    public static String ack(String clientNonceHex, String moduleNonceHex, String token) {
        return "ACK {\"v\":" + PROTOCOL_VERSION
                + ",\"proof\":\"" + proof(clientNonceHex, token) + "\""
                + ",\"nonce\":\"" + moduleNonceHex + "\"}";
    }

    /** Client reply to the module's challenge. */
    public static String auth(String moduleNonceHex, String token) {
        return "AUTH {\"v\":" + PROTOCOL_VERSION
                + ",\"proof\":\"" + proof(moduleNonceHex, token) + "\"}";
    }

    /** Sent once the module has accepted AUTH. */
    public static String ready() {
        return "READY {\"ok\":true}";
    }

    /** Sent instead of READY, or instead of an ACK, when a step fails. */
    public static String ackBad(String reason) {
        return "NACK {\"reason\":\"" + reason + "\"}";
    }

    /**
     * Hex of hmacSHA256(token, nonce), or null when the MAC cannot be computed.
     *
     * <p>Returning null rather than an empty string matters: {@link #matches} treats an empty
     * string as a value, so a failure to construct the MAC would otherwise let an
     * {@code AUTH {"proof":""}} through.
     */
    public static String proof(String nonceHex, String token) {
        if (nonceHex == null || token == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(nonceHex.getBytes(StandardCharsets.UTF_8));
            return Digest.bytesToHex(out);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Generates a 16-byte random nonce as hex. */
    public static String newNonce() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Digest.bytesToHex(b);
    }

    /** Extracts the nonce from a HELLO or ACK line. */
    public static String extractNonce(String line) {
        return extractField(line, "nonce");
    }

    /** Extracts the proof from an ACK or AUTH line. */
    public static String extractProof(String line) {
        return extractField(line, "proof");
    }

    public static boolean isHello(String s) {
        return s != null && s.startsWith("HELLO");
    }

    public static boolean isAuth(String s) {
        return s != null && s.startsWith("AUTH");
    }

    /** Constant-time comparison, so a wrong proof cannot be found byte by byte. */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null || expected.isEmpty()) return false;
        if (expected.length() != actual.length()) return false;
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ actual.charAt(i);
        }
        return diff == 0;
    }

    private static String extractField(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int start = i + needle.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }
}
