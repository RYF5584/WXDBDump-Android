package io.github.ryf5584.wxdbdump.payload;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.ryf5584.wxdbdump.payload.crypto.UinResolver;
import io.github.ryf5584.wxdbdump.payload.paths.WeChatPaths;

/**
 * The uin is checked against the directory name, which is what stops one account's identity
 * being attributed to another.
 */
class UinResolverTest {

    private static final int UIN = 123456789;

    @Test
    @DisplayName("a uin is confirmed only by the directory name it produces")
    void identityCheck() {
        assertTrue(UinResolver.matches(UIN, WeChatPaths.accountDirName(UIN)));
        // A directory built some other way must not be claimed by any uin.
        assertFalse(UinResolver.matches(UIN, "00000000000000000000000000000000"));
        assertFalse(UinResolver.matches(0, WeChatPaths.accountDirName(0)));
        assertFalse(UinResolver.matches(UIN, null));
    }

    @Test
    @DisplayName("a mask is not reversible back to a uin")
    void maskIsOneWay() {
        assertTrue(UinResolver.maskUin(0).equals("--"));
        assertTrue(UinResolver.maskUin(UIN).startsWith("wxid_"));
        assertFalse(UinResolver.maskUin(UIN).equals(UIN));
    }
}
