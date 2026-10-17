package io.github.ryf5584.wxdbdump.payload.proto;

/**
 * Loopback channel constants, which the app and the module must agree on.
 *
 * <p>These are plain Java constants rather than per-module BuildConfig fields, because
 * a library module cannot see the host applicationId and duplicated values drift apart.
 * The port and token live here and nowhere else, so both processes read the same values
 * without a shared file or a ContentProvider.
 *
 * <p>Security: the socket binds to 127.0.0.1 only and both directions of an HMAC
 * handshake must pass before a command is accepted. Be aware that TOKEN is a build-time
 * constant published with the source, so it filters peers that have not read this
 * repository rather than authenticating the app. See SECURITY.md.
 */
public final class Channel {

    /** Loopback port. */
    public static final int PORT = 27183;

    /** Shared handshake secret. */
    public static final String TOKEN = "9f2c7be41a6d8530c4e1b7a25d309f6c";

    /**
     * Client version, reported in HELLO. Nothing consumes it yet: the server ignores the
     * field and the client does not check the version it gets back, so a protocol change
     * would desynchronise silently.
     */
    public static final String CLIENT_VERSION = "1.0.0";

    private Channel() {
    }
}
