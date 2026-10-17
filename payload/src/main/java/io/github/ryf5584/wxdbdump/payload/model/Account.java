package io.github.ryf5584.wxdbdump.payload.model;

import java.util.ArrayList;
import java.util.List;

/** One exportable WeChat account. */
public final class Account {

    /** Account directory name, {@code md5hex("mm"+uin)}. */
    public final String dirName;
    /** Absolute account directory path. */
    public final String dir;
    /** Recovered uin, or 0 when recovery failed. */
    public int uin;
    /** How the uin was recovered, for display and troubleshooting. */
    public String uinSource = "";
    /** Nickname, optional. */
    public String nickname = "";
    /** wxid, optional. */
    public String wxid = "";
    /** How the wxid was recovered, for display and troubleshooting. */
    public String wxidSource = "";
    /** User-set alias, which differs from the wxid. */
    public String alias = "";
    /** Bound phone number, optional. */
    public String mobile = "";
    /** Absolute avatar path, set only when the file really exists. */
    public String avatarPath = "";
    /** Number of databases. */
    public int dbCount;
    /** Total size of all databases. */
    public long totalBytes;
    /** Last activity time in milliseconds, taken from the account directory. */
    public long lastActiveMs;
    /** Whether the main database can be decrypted. */
    public boolean decryptable;
    /** Reason shown to the user when decryption is impossible. */
    public String note = "";

    public final List<DbFile> dbs = new ArrayList<>();

    public Account(String dirName, String dir) {
        this.dirName = dirName;
        this.dir = dir;
    }

    /** First 8 characters of the directory name, used as a short label. */
    public String shortId() {
        return dirName.length() >= 8 ? dirName.substring(0, 8) : dirName;
    }

    @Override
    public String toString() {
        return "Account(" + dirName + ", uin=" + uin + ", dbs=" + dbCount + ")";
    }
}
