package io.github.ryf5584.wxdbdump.payload.identity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.ryf5584.wxdbdump.payload.EngineHost;
import io.github.ryf5584.wxdbdump.payload.Digest;
import io.github.ryf5584.wxdbdump.payload.Log;
import io.github.ryf5584.wxdbdump.payload.paths.WeChatPaths;

/**
 * Account identity: nickname, wxid, alias, bound phone and avatar.
 * Read from plaintext preference XML and avatar files, never from obfuscated names.
 *
 * Global preferences describe only the last signed-in account, matched by last_login_uin;
 * historical accounts stay empty rather than being mismatched.
 */
public final class AccountIdentity {

    /** Global signed-in identity. */
    public static final class Prefs {
        public String wxid = "";
        public String nickname = "";
        public String alias = "";
        public String mobile = "";
        public int uin = 0;

        public boolean any() {
            return !wxid.isEmpty() || !nickname.isEmpty();
        }

        @Override
        public String toString() {
            return "wxid=" + wxid + " nick=" + nickname + " alias=" + alias + " uin=" + uin;
        }
    }

    private static final Pattern STRING_PREF =
            Pattern.compile("<string\\s+name=\"([^\"]+)\"\\s*>([^<]*)</string>");
    private static final Pattern INT_PREF =
            Pattern.compile("<int\\s+name=\"([^\"]+)\"\\s+value=\"(-?\\d+)\"\\s*/>");
    private static final Pattern LONG_PREF =
            Pattern.compile("<long\\s+name=\"([^\"]+)\"\\s+value=\"(-?\\d+)\"\\s*/>");

    private AccountIdentity() {
    }

    /** Reads the global signed-in identity, returning an empty value rather than throwing. */
    public static Prefs read(EngineHost host) {
        Prefs p = new Prefs();
        try {
            byte[] raw = host.files().readAll(WeChatPaths.PREFS_MAIN);
            if (raw == null || raw.length == 0) {
                Log.d("ident", "读不到主 prefs：" + WeChatPaths.PREFS_MAIN);
                return p;
            }
            String xml = new String(raw, StandardCharsets.UTF_8);
            Matcher ms = STRING_PREF.matcher(xml);
            while (ms.find()) {
                String k = ms.group(1);
                String v = unescape(ms.group(2));
                if (WeChatPaths.KEY_LOGIN_WXID.equals(k)) p.wxid = v;
                else if (WeChatPaths.KEY_LAST_NICKNAME.equals(k)) p.nickname = v;
                else if (WeChatPaths.KEY_LAST_ALIAS.equals(k)) p.alias = v;
                else if (WeChatPaths.KEY_LAST_MOBILE.equals(k)) p.mobile = v;
                // last_login_uin is stored as a string rather than an int, so it is read here too.
                // Missing it leaves the uin at zero and misattributes the identity to every account.
                else if (WeChatPaths.KEY_LAST_UIN.equals(k)) p.uin = safeInt(v);
            }
            if (p.uin == 0) {
                Matcher mi = INT_PREF.matcher(xml);
                while (mi.find()) {
                    if (WeChatPaths.KEY_LAST_UIN.equals(mi.group(1))) {
                        p.uin = safeInt(mi.group(2));
                    }
                }
            }
            if (p.uin == 0) {
                Matcher ml = LONG_PREF.matcher(xml);
                while (ml.find()) {
                    if (WeChatPaths.KEY_LAST_UIN.equals(ml.group(1))) {
                        p.uin = safeInt(ml.group(2));
                    }
                }
            }
            Log.i("ident", "全局登录身份：" + p);
        } catch (Throwable t) {
            Log.w("ident", "解析登录身份失败", t);
        }
        return p;
    }

    /**
     * Resolves an account's avatar path, which the caller must confirm exists.
     *
     * <p>Normally the path is derived from the wxid. Historical accounts often have no
     * recoverable wxid, however: their uin cannot be read, so the only wxid we can show is a
     * masked placeholder, and the derived path points nowhere even though the avatar is on
     * disk. For those, the account's own avatar tree is scanned instead.
     *
     * @param wxid the account's wxid, possibly a masked placeholder or empty
     */
    public static String avatarPath(EngineHost host, String accountDir, String wxid) {
        if (wxid != null && wxid.startsWith("wxid_") && !wxid.contains("\u2026")) {
            String dir = WeChatPaths.avatarDirFor(accountDir, wxid);
            String hd = dir + WeChatPaths.avatarHdName(wxid);
            if (exists(host, hd)) return hd;
            String normal = dir + WeChatPaths.avatarName(wxid);
            if (exists(host, normal)) return normal;
            Log.d("ident", "no avatar at the path derived from the wxid: " + dir);
        }
        return scanForOwnAvatar(host, accountDir);
    }

    /**
     * Databases inside an account directory that name the account's own wxid in plaintext.
     *
     * <p>TextStatusSessionInfo.selfUsername in the first is the account's own username, and
     * the others record it alongside its moments and its contacts. All three are unencrypted.
     */
    private static final String[] SELF_WXID_SOURCES = {
        "TextStatus.db", "SnsMicroMsg.db", "HashInfo.db",
    };

    /** A wxid as it appears in those databases. */
    private static final Pattern WXID = Pattern.compile("wxid_[0-9a-zA-Z_]{6,32}");

    /** Reads at most this much of a candidate file; the wxid is near the front. */
    private static final int SELF_WXID_SCAN_BYTES = 1 << 20;

    /**
     * Recovers an account's own wxid from the databases inside its directory.
     *
     * <p>When the global preferences describe a different account, this one's wxid is unknown,
     * and everything keyed on it - the avatar, the export folder name - falls back to a
     * placeholder. The wxid is still on disk: the account's own plaintext databases record it.
     *
     * <p>The avatar file is named {@code user_hd_<md5hex(wxid)>.png}, which makes the match
     * exact. Contacts' wxids appear in the same files, so a plain string search would pick the
     * wrong one; requiring the hash to agree cannot.
     *
     * @param avatarPath the account's own avatar, as returned by {@link #avatarPath}
     */
    public static String ownWxid(EngineHost host, String accountDir, String avatarPath) {
        if (avatarPath == null || avatarPath.isEmpty()) return "";

        String want = md5FromAvatarName(avatarPath);
        if (want == null) return "";

        for (String name : SELF_WXID_SOURCES) {
            String path = accountDir + name;
            if (!exists(host, path)) continue;

            byte[] raw;
            try {
                raw = host.files().readHead(path, SELF_WXID_SCAN_BYTES);
            } catch (Throwable t) {
                continue;
            }
            if (raw == null || raw.length == 0) continue;

            Matcher m = WXID.matcher(new String(raw, StandardCharsets.ISO_8859_1));
            while (m.find()) {
                String candidate = m.group();
                if (Digest.md5Hex(candidate).equals(want)) {
                    Log.i("ident", "own wxid from " + name + ": " + candidate);
                    return candidate;
                }
            }
        }
        return "";
    }

    /** The hash embedded in a {@code user_hd_<hash>.png} or {@code user_<hash>.png} name. */
    private static String md5FromAvatarName(String avatarPath) {
        String name = baseName(avatarPath);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        if (name.startsWith("user_hd_")) name = name.substring("user_hd_".length());
        else if (name.startsWith("user_")) name = name.substring("user_".length());
        else return null;
        return name.length() == 32 ? name : null;
    }

    /**
     * Finds an account's own avatar without knowing its wxid.
     *
     * <p>An account's avatar sits in its own {@code avatar/} tree next to its contacts'. The
     * filename is a hash, so it cannot be tied back to the account directly. Two things make
     * it findable anyway: WeChat keeps a copy of each signed-in account's own avatar in the
     * shared {@code last_avatar_dir}, which separates the account's avatar from its contacts';
     * and WeChat prunes contacts' avatars over time, so a dormant account is often left with
     * only its own.
     *
     * <p>Only an unambiguous answer is returned. Guessing between several candidates would
     * put someone else's face on the account.
     */
    private static String scanForOwnAvatar(EngineHost host, String accountDir) {
        String[] all;
        try {
            all = host.files().walk(WeChatPaths.avatarRoot(accountDir));
        } catch (Throwable t) {
            return null;
        }
        if (all == null || all.length == 0) return null;

        List<String> hd = new ArrayList<>();
        for (String path : all) {
            String name = baseName(path);
            if (name.startsWith("user_hd_") && name.endsWith(".png")) hd.add(path);
        }
        if (hd.isEmpty()) return null;

        List<String> signedIn = new ArrayList<>();
        for (String path : hd) {
            if (exists(host, WeChatPaths.LAST_AVATAR_DIR + baseName(path))) signedIn.add(path);
        }
        if (signedIn.size() == 1) return signedIn.get(0);
        if (signedIn.isEmpty() && hd.size() == 1) return hd.get(0);

        Log.d("ident", "avatar candidates are ambiguous (" + hd.size() + " found); none used");
        return null;
    }

    private static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static boolean exists(EngineHost host, String path) {
        try {
            return host.files().exists(path);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Unescapes XML entities, which appear in nicknames. */
    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
