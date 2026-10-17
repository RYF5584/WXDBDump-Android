package io.github.ryf5584.wxdbdump.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.ryf5584.wxdbdump.payload.paths.WeChatPaths;

class PathsAndDigestTest {

    @Test
    @DisplayName("md5Hex is lowercase hex of the UTF-8 bytes")
    void md5HexFormat() {
        // The account directory name and the key both derive from this, so its shape matters.
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Digest.md5Hex(""));
        assertEquals("0cc175b9c0f1b6a831c399e269772661", Digest.md5Hex("a"));
        String h = Digest.md5Hex("mm123456789");
        assertEquals(32, h.length());
        assertEquals(h.toLowerCase(), h);
        assertEquals(h, Digest.md5Hex("mm123456789"));
    }

    @Test
    @DisplayName("an account directory is the md5 of mm plus the uin")
    void accountDirectoryName() {
        assertEquals(Digest.md5Hex("mm123456789"), WeChatPaths.accountDirName(123456789));
    }

    @Test
    @DisplayName("only 32 hex characters count as an account directory")
    void validatesAccountDirectoryNames() {
        assertTrue(WeChatPaths.isAccountDirName("2c644d942ab78a4d933ed0d19f881307"));
        assertFalse(WeChatPaths.isAccountDirName("27D0043E49040749ABD20C964690BA6F"));
        assertFalse(WeChatPaths.isAccountDirName("2c644d942ab78a4d933ed0d19f88130"));
        assertFalse(WeChatPaths.isAccountDirName("2c644d942ab78a4d933ed0d19f8813070"));
        assertFalse(WeChatPaths.isAccountDirName("EnResDown"));
        assertFalse(WeChatPaths.isAccountDirName(null));
    }

    @Test
    @DisplayName("the avatar path is sharded two levels deep by the hash of the wxid")
    void avatarPathLayout() {
        String wxid = "wxid_example000000";
        String md5 = Digest.md5Hex(wxid);
        String dir = WeChatPaths.avatarDirFor("/account/", wxid);
        assertTrue(dir.endsWith(md5.substring(0, 2) + "/" + md5.substring(2, 4) + "/"), dir);
        assertEquals("user_hd_" + md5 + ".png", WeChatPaths.avatarHdName(wxid));
        assertEquals("user_" + md5 + ".png", WeChatPaths.avatarName(wxid));
    }

    @Test
    @DisplayName("a null path component is refused rather than throwing")
    void avatarOfNothing() {
        assertNull(WeChatPaths.avatarDirFor("/account/", null));
    }
}
