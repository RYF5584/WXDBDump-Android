package io.github.ryf5584.wxdbdump.payload.paths;

/**
 * WeChat storage paths. All of them come from the on-disk layout rather than obfuscated names.
 *
 * <p>Verified against WeChat 8.0.72:
 * <ul>
 *   <li>data root = {@code getFilesDir().getParentFile()} = {@code /data/user/0/com.tencent.mm/}</li>
 *   <li>account root = data root + {@code MicroMsg/}</li>
 *   <li>account subdirectory = {@code md5hex("mm" + uin)}, confirmed at two call sites</li>
 *   <li>main database = {@code <account>/EnMicroMsg.db}</li>
 *   <li>account MMKV = {@code <account>/mmkv_private/}</li>
 *   <li>device id file = {@code files/KeyInfo.bin}, RC4-encrypted with a fixed key</li>
 *   <li>current uin = {@code default_uin} in {@code system_config_prefs.xml}</li>
 * </ul>
 *
 * <p>These paths must survive as long as WeChat reads old data, so they are version-stable.
 */
public final class WeChatPaths {

    /** WeChat package name. */
    public static final String WECHAT_PKG = "com.tencent.mm";

    /** data root. /data/user/0 rather than /data/data, which is a symlink on Android 10+. */
    public static final String DATA_ROOT = "/data/user/0/com.tencent.mm";
    /** Alias kept for compatibility. */
    public static final String DATA_ROOT_ALT = "/data/data/com.tencent.mm";

    /** Account data root: {@code <DATA_ROOT>/MicroMsg/}. */
    public static final String MICRO_MSG = DATA_ROOT + "/MicroMsg/";

    /** WeChat's private files directory. */
    public static final String FILES = DATA_ROOT + "/files/";

    /** SharedPreferences directory. */
    public static final String SHARED_PREFS = DATA_ROOT + "/shared_prefs/";

    /**
     * Holds the device id WeChat uses for key derivation, RC4-encrypted with {@code _wEcHAT_}.
     * See {@link io.github.ryf5584.wxdbdump.payload.crypto.KeyDerivation}.
     */
    public static final String KEY_INFO_BIN = FILES + "KeyInfo.bin";

    /**
     * Marks the directories of accounts that have signed in: each empty file's name is one
     * account directory. This is WeChat's own authoritative list and far more accurate than
     * guessing from 32-hex names, since resource directories look identical.
     */
    public static final String IDENTITY_RECORDS = FILES + "identity_records";

    /** Preferences file holding the current uin. */
    public static final String SYSTEM_CONFIG_PREFS = SHARED_PREFS + "system_config_prefs.xml";

    /**
     * Main preferences file: holds the signed-in identity (wxid, nickname, alias, phone).
     *
     * <p>Key names as observed on WeChat 8.0.72:
     * <pre>
     *   login_weixin_username   = wxid of the signed-in account
     *   last_login_nick_name    = nickname
     *   last_login_alias        = user-set alias
     *   last_login_bind_mobile  = bound phone number
     *   last_login_uin          = uin the identity belongs to
     * </pre>
     * Plain XML, version-stable because WeChat has to keep reading old configuration.
     */
    public static final String PREFS_MAIN = SHARED_PREFS + "com.tencent.mm_preferences.xml";

    /** Preference key holding the uin. */
    public static final String KEY_DEFAULT_UIN = "default_uin";

    /** Preference keys holding the signed-in identity. */
    public static final String KEY_LOGIN_WXID = "login_weixin_username";
    public static final String KEY_LAST_NICKNAME = "last_login_nick_name";
    public static final String KEY_LAST_ALIAS = "last_login_alias";
    public static final String KEY_LAST_MOBILE = "last_login_bind_mobile";
    public static final String KEY_LAST_UIN = "last_login_uin";

    /** Avatar directory inside an account directory. */
    public static final String AVATAR_DIR = "avatar";

    /**
     * WeChat keeps one copy of each signed-in account's own avatar here, named by the
     * md5 of that account's wxid. It is the only place an account's avatar can be found
     * when its wxid is not recoverable, because the name alone does not reveal which
     * account it belongs to.
     */
    public static final String LAST_AVATAR_DIR = MICRO_MSG + "last_avatar_dir/";

    /** Account directory prefix: the name is {@code md5hex("mm" + uin)}. */
    public static final String ACCOUNT_ID_PREFIX = "mm";

    /** Pattern matching an account directory name: 32 lowercase hex characters. */
    public static final String ACCOUNT_DIR_REGEX = "^[0-9a-f]{32}$";

    /** Account-level MMKV subdirectory name. */
    public static final String MMKV_PRIVATE = "mmkv_private";

    /** MMKV file holding account configuration, including userinfo. */
    public static final String MMKV_CONFIG_STORAGE = "ConfigStorage1";

    /** Main database file name. */
    public static final String MAIN_DB = "EnMicroMsg.db";

    /** Database file extension. */
    public static final String DB_SUFFIX = ".db";

    /** Plaintext SQLite header. */
    public static final byte[] SQLITE_HEADER = {
            'S', 'Q', 'L', 'i', 't', 'e', ' ', 'f', 'o', 'r', 'm', 'a', 't', ' ', '3', 0
    };

    private WeChatPaths() {
    }

    /** Account directory name for a uin: 32 lowercase hex characters. */
    public static String accountDirName(int uin) {
        return io.github.ryf5584.wxdbdump.payload.Digest.md5Hex(ACCOUNT_ID_PREFIX + uin);
    }

    /** Absolute account directory path for a uin. */
    public static String accountDir(int uin) {
        return MICRO_MSG + accountDirName(uin) + "/";
    }

    /** MMKV directory inside an account directory. */
    public static String mmkvPrivateDir(String accountDir) {
        return accountDir + MMKV_PRIVATE + "/";
    }

    /** ConfigStorage1 MMKV file inside an account directory. */
    public static String configStorageFile(String accountDir) {
        return mmkvPrivateDir(accountDir) + MMKV_CONFIG_STORAGE;
    }

    /** Main database path inside an account directory. */
    public static String mainDb(String accountDir) {
        return accountDir + MAIN_DB;
    }

    /** Whether a name is a valid account directory. */
    public static boolean isAccountDirName(String name) {
        return name != null && name.matches(ACCOUNT_DIR_REGEX);
    }

    /** Avatar root inside an account directory. */
    public static String avatarRoot(String accountDir) {
        return accountDir + AVATAR_DIR + "/";
    }

    /**
     * Avatar file path, sharded by two levels of hex.
     *
     * <p>Layout: {@code avatar/<md5(x)[0:2]>/<md5(x)[2:4]>/user_hd_<md5(x)>.png}, where
     * {@code x} is the wxid. Falls back to {@code user_<md5(x)>.png} without an HD variant.
     */
    public static String avatarDirFor(String accountDir, String wxid) {
        String md5 = md5OrNull(wxid);
        if (md5 == null) return null;
        return avatarRoot(accountDir) + md5.substring(0, 2) + "/" + md5.substring(2, 4) + "/";
    }

    /** HD avatar file name, or null when there is no wxid to name it after. */
    public static String avatarHdName(String wxid) {
        String md5 = md5OrNull(wxid);
        return md5 == null ? null : "user_hd_" + md5 + ".png";
    }

    /**
     * Hashes a wxid, treating an absent one as absent rather than hashing the empty string.
     *
     * <p>Callers walk a tree of accounts, some of which have no recoverable wxid. Returning
     * null rather than throwing turns a crash into a path that simply is not found.
     */
    private static String md5OrNull(String wxid) {
        if (wxid == null || wxid.isEmpty()) return null;
        return io.github.ryf5584.wxdbdump.payload.Digest.md5Hex(wxid);
    }

    /** Regular avatar file name. */
    public static String avatarName(String wxid) {
        String md5 = md5OrNull(wxid);
        return md5 == null ? null : "user_" + md5 + ".png";
    }
}
