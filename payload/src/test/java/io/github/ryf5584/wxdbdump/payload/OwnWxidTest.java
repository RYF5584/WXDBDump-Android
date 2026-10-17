package io.github.ryf5584.wxdbdump.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.ryf5584.wxdbdump.payload.identity.AccountIdentity;

class OwnWxidTest {

    private static final String WXID = "wxid_example000000";
    private static final String HASH = Digest.md5Hex(WXID);
    private static final String AVATAR = "/acct/avatar/" + HASH.substring(0, 2) + "/"
            + HASH.substring(2, 4) + "/user_hd_" + HASH + ".png";

    private static final class FakeHost implements EngineHost {
        final Map<String, byte[]> files = new HashMap<>();
        void put(String p, String s) { files.put(p, s.getBytes(StandardCharsets.UTF_8)); }

        @Override public EngineHost.FileSource files() {
            return new EngineHost.FileSource() {
                @Override public boolean exists(String p) { return files.containsKey(p); }
                @Override public String[] list(String d) { return new String[0]; }
                @Override public long size(String p) { return files.containsKey(p) ? files.get(p).length : -1; }
                @Override public byte[] readHead(String p, int n) { return files.get(p); }
                @Override public byte[] readAll(String p) { return files.get(p); }
                @Override public boolean copyTo(String s, String d) { return false; }
                @Override public String[] walk(String r) { return new String[0]; }
            };
        }
        @Override public int pid() { return 1; }
        @Override public String processName() { return "test"; }
        @Override public ClassLoader loader() { return OwnWxidTest.class.getClassLoader(); }
        @Override public Object globalContext() { return null; }
        @Override public String filesDir() { return "/files"; }
        @Override public String cacheDir() { return "/cache"; }
        @Override public io.github.ryf5584.wxdbdump.payload.platform.Hooks hooks() { return null; }
    }

    @Test
    @DisplayName("the account's own wxid is recovered from its own database")
    void recoversFromTheRightDatabase() {
        FakeHost host = new FakeHost();
        // TextStatusSessionInfo records the account's own username alongside a contact's.
        host.put("/acct/TextStatus.db",
                "selfUsername=" + WXID + ";talker=wxid_someoneelse123456");
        assertEquals(WXID, AccountIdentity.ownWxid(host, "/acct/", AVATAR));
    }

    @Test
    @DisplayName("a contact's wxid in the same file is never mistaken for the account's")
    void rejectsWxidsThatDoNotMatchTheAvatar() {
        FakeHost host = new FakeHost();
        // Only a contact appears; the avatar hash does not name it.
        host.put("/acct/TextStatus.db", "talker=wxid_someoneelse123456");
        assertEquals("", AccountIdentity.ownWxid(host, "/acct/", AVATAR));
    }

    @Test
    @DisplayName("with no avatar there is no hash to check against, so nothing is claimed")
    void needsTheAvatarToConfirm() {
        FakeHost host = new FakeHost();
        host.put("/acct/TextStatus.db", "selfUsername=" + WXID);
        assertEquals("", AccountIdentity.ownWxid(host, "/acct/", ""));

        // A name that is not an avatar filename carries no hash either.
        assertTrue(AccountIdentity.ownWxid(host, "/acct/", "/acct/avatar/x/y/other.png").isEmpty());
    }
}
