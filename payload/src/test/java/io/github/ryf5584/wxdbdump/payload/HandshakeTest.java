package io.github.ryf5584.wxdbdump.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.ryf5584.wxdbdump.payload.proto.Handshake;

/**
 * The handshake is the only thing standing between another app on the device and the
 * decrypted databases, so its edge cases are worth pinning down.
 */
class HandshakeTest {

    private static final String TOKEN = "test-token";

    @Test
    @DisplayName("a proof is a function of the token and the nonce")
    void proofIsDeterministic() {
        String a = Handshake.proof("nonce-a", TOKEN);
        assertEquals(a, Handshake.proof("nonce-a", TOKEN));
        assertNotEquals(a, Handshake.proof("nonce-b", TOKEN));
        assertNotEquals(a, Handshake.proof("nonce-a", "other-token"));
    }

    @Test
    @DisplayName("an ACK carries both the proof and a fresh nonce for the client to sign")
    void ackCarriesAChallenge() {
        String ack = Handshake.ack("client-nonce", "module-nonce", TOKEN);
        assertEquals(Handshake.proof("client-nonce", TOKEN), Handshake.extractProof(ack));
        assertEquals("module-nonce", Handshake.extractNonce(ack));
    }

    @Test
    @DisplayName("a client proves itself over the module's nonce, not its own")
    void authSignsTheModuleNonce() {
        String auth = Handshake.auth("module-nonce", TOKEN);
        assertTrue(Handshake.isAuth(auth));
        assertEquals(Handshake.proof("module-nonce", TOKEN), Handshake.extractProof(auth));
        // Signing the client's own nonce would be replayable by anyone who saw the ACK.
        assertNotEquals(Handshake.proof("client-nonce", TOKEN), Handshake.extractProof(auth));
    }

    @Test
    @DisplayName("proofs match only when equal, and never for a missing or empty expectation")
    void matchesRejectsAbsentProofs() {
        String p = Handshake.proof("n", TOKEN);
        assertTrue(Handshake.matches(p, p));
        assertFalse(Handshake.matches(p, Handshake.proof("other", TOKEN)));
        assertFalse(Handshake.matches(p, null));
        assertFalse(Handshake.matches(null, p));
        // An empty expectation must never be treated as "nothing to check".
        assertFalse(Handshake.matches("", ""));
        assertFalse(Handshake.matches("", "anything"));
        assertFalse(Handshake.matches(p, ""));
    }

    @Test
    @DisplayName("a proof cannot be produced when the inputs are missing")
    void proofRefusesMissingInputs() {
        assertNull(Handshake.proof(null, TOKEN));
        assertNull(Handshake.proof("nonce", null));
    }

    @Test
    @DisplayName("nonces differ between calls")
    void noncesAreNotRepeated() {
        String a = Handshake.newNonce();
        assertNotNull(a);
        assertEquals(32, a.length(), "16 bytes as hex");
        assertNotEquals(a, Handshake.newNonce());
    }

    @Test
    @DisplayName("frame prefixes are distinguished")
    void recognisesItsOwnFrames() {
        assertTrue(Handshake.isHello("HELLO {}"));
        assertTrue(Handshake.isAuth("AUTH {}"));
        assertFalse(Handshake.isHello("AUTH {}"));
        assertFalse(Handshake.isAuth("HELLO {}"));
        assertFalse(Handshake.isHello(null));
    }
}
