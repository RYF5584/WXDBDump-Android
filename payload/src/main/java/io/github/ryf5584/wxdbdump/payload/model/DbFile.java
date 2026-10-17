package io.github.ryf5584.wxdbdump.payload.model;

/** One database file found during enumeration. */
public final class DbFile {

    /** File name, e.g. EnMicroMsg.db. */
    public final String name;
    /** Absolute path. */
    public final String path;
    /** Size in bytes. */
    public final long sizeBytes;
    /** Whether the file is encrypted, decided from the first 16 bytes rather than a name list. */
    public boolean encrypted;
    /** Whether a -wal sidecar exists. */
    public boolean hasWal;
    /** Whether a -shm sidecar exists. */
    public boolean hasShm;
    /** Whether a -journal sidecar exists. */
    public boolean hasJournal;
    /** Business category. UI only. */
    public DbCategory category;
    /** Category key matching {@link DbCatalog}, used by the UI for labels and colours. */
    public String categoryKey;
    /** Purpose text shown to the user. */
    public String description;
    /**
     * Whether the database is disposable: a cache, a stats buffer or an index that can be
     * rebuilt from the main database. A failed export of such a file carries no data loss,
     * so callers may skip it instead of reporting an error.
     */
    public final boolean nonEssential;

    public DbFile(String name, String path, long sizeBytes) {
        this.name = name;
        this.path = path;
        this.sizeBytes = sizeBytes;
        this.category = DbCategory.guess(name);
        this.categoryKey = DbCatalog.category(name);
        this.description = DbCatalog.describe(name);
        this.nonEssential = DbCatalog.isNonEssential(name);
    }

    /** Human-readable size. */
    public String readableSize() {
        return formatSize(sizeBytes);
    }

    public static String formatSize(long bytes) {
        if (bytes < 0) return "--";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(java.util.Locale.US, "%.2f GB", gb);
    }

    @Override
    public String toString() {
        return name + "(" + readableSize() + (encrypted ? ",encrypted" : ",plaintext") + ")";
    }
}
