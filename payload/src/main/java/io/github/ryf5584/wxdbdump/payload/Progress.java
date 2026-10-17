package io.github.ryf5584.wxdbdump.payload;

/**
 * Enumeration progress callback.
 *
 * <p>Enumerating one account lists directories, stats every database, reads headers and
 * resolves the uin. That takes seconds, and without updates the app looks frozen, so
 * progress is pushed back over the loopback channel as {@code #P} frames.
 *
 * <p>Implementations must be non-blocking and must never throw: they are called
 * synchronously on the enumeration thread.
 */
public interface Progress {

    /** No-op implementation. */
    Progress NONE = (done, total, stage) -> {
    };

    /**
     * @param done  items finished, or 0 when meaningless
     * @param total item count, or 0 when unknown (indeterminate)
     * @param stage current stage, shown to the user as-is
     */
    void on(int done, int total, String stage);
}
