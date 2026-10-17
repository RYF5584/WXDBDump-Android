package io.github.ryf5584.wxdbdump.payload.discovery;

/**
 * One row of a single-pass scan.
 *
 * <p>It exists because one directory listing costs an su process in root mode, and walking
 * thousands of directories takes minutes. Finding databases, reading sizes and headers
 * and checking sidecars in one shell command brings that down to a single process.
 */
public final class DbScan {

    /** Absolute path including the file name. */
    public final String path;
    /** File name. */
    public final String name;
    /** Size in bytes. */
    public final long size;
    public final boolean wal;
    public final boolean shm;
    public final boolean journal;
    /** Whether the file is encrypted, decided from the first 16 bytes. */
    public final boolean encrypted;

    public DbScan(String path, String name, long size,
                  boolean wal, boolean shm, boolean journal, boolean encrypted) {
        this.path = path;
        this.name = name;
        this.size = size;
        this.wal = wal;
        this.shm = shm;
        this.journal = journal;
        this.encrypted = encrypted;
    }
}
