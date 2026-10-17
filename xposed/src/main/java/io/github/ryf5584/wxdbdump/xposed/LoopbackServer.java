package io.github.ryf5584.wxdbdump.xposed;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.ryf5584.wxdbdump.payload.Digest;
import io.github.ryf5584.wxdbdump.payload.Log;
import io.github.ryf5584.wxdbdump.payload.engine.KeyVault;
import io.github.ryf5584.wxdbdump.payload.discovery.DatabaseEnumerator;
import io.github.ryf5584.wxdbdump.payload.model.Account;
import io.github.ryf5584.wxdbdump.payload.model.DbFile;
import io.github.ryf5584.wxdbdump.payload.proto.Handshake;
import io.github.ryf5584.wxdbdump.payload.proto.Wire;

/**
 * Loopback server inside WeChat's process. It is the only channel through which the app
 *
 * <p>Why loopback TCP instead of a LocalSocket: cross-uid LocalSocket connections are
 * restricted by SELinux, while loopback TCP is unaffected by uid or scoped storage. Both
 * sides only need INTERNET, and streaming large files works naturally.
 *
 * <p>Binds to 127.0.0.1 only, and a peer must pass both directions of an HMAC handshake
 * before it can issue a command. TOKEN is a build-time constant that ships in the APK and
 * in the repository, so the handshake filters peers that have not read the source rather
 * than authenticating the app; see Handshake and SECURITY.md.
 */
public final class LoopbackServer {

    private final WxDbEngine engine;
    private final int port;
    private final String token;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Handshake and per-read deadline for an accepted connection. */
    private static final int HANDSHAKE_TIMEOUT_MS = 15_000;

    private ServerSocket server;

    public LoopbackServer(WxDbEngine engine, int port, String token) {
        this.engine = engine;
        this.port = port;
        this.token = token;
    }

    /** Starts the server. Every exception is swallowed; the module must never crash WeChat. */
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        Thread t = new Thread(this::acceptLoop, "wxdump-loopback");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running.set(false);
        try {
            if (server != null) server.close();
        } catch (Throwable ignored) {
        }
    }

    private void acceptLoop() {
        try {
            server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
            Log.i("net", "loopback server listening on 127.0.0.1:" + port);
        } catch (Throwable t) {
            Log.e("net", "loopback server failed to start, port=" + port, t);
            running.set(false);
            return;
        }
        while (running.get()) {
            try {
                final Socket s = server.accept();
                Thread h = new Thread(() -> handle(s), "wxdump-conn");
                h.setDaemon(true);
                h.start();
            } catch (Throwable t) {
                if (running.get()) Log.w("net", "accept failed: " + t);
            }
        }
    }

    private void handle(Socket s) {
        long t0 = System.currentTimeMillis();
        try {
            s.setTcpNoDelay(true);
            // Without a read timeout a peer can connect and then stall, holding its thread
            // inside WeChat's process indefinitely. Commands are authenticated; resources
            // are not, and a single local app could open connections until the process dies.
            s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(s.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream());
            Log.i("net", "new connection " + s.getRemoteSocketAddress());

            // ---- Handshake ----
            Wire.Frame f = Wire.read(in);
            String hello = f.text();
            if (f.type != Wire.TYPE_TEXT || !Handshake.isHello(hello)) {
                Log.w("net", "handshake failed, first frame is not HELLO (type=" + f.type + ", head=" + head(hello));
                Wire.writeText(out, Handshake.ackBad("BAD_HELLO"));
                return;
            }
            String nonce = Handshake.extractNonce(hello);
            if (nonce == null) {
                Log.w("net", "handshake failed, no nonce in HELLO (head=" + head(hello) + ")");
                Wire.writeText(out, Handshake.ackBad("NO_NONCE"));
                return;
            }
            Log.i("net", "received HELLO nonce=" + nonce);

            // The module signs the client nonce, then issues one of its own. Without the second
            // half any peer could open the port and issue DUMP straight away.
            String moduleNonce = Handshake.newNonce();
            Wire.writeText(out, Handshake.ack(nonce, moduleNonce, token));

            Wire.Frame authFrame = Wire.read(in);
            String authLine = authFrame.text();
            if (authFrame.type != Wire.TYPE_TEXT || !Handshake.isAuth(authLine)) {
                Log.w("net", "handshake failed, expected AUTH (type=" + authFrame.type
                        + ", head=" + head(authLine) + ")");
                Wire.writeText(out, Handshake.ackBad("BAD_AUTH"));
                return;
            }
            if (!Handshake.matches(Handshake.proof(moduleNonce, token),
                    Handshake.extractProof(authLine))) {
                Log.w("net", "handshake failed, AUTH proof mismatch");
                Wire.writeText(out, Handshake.ackBad("BAD_TOKEN"));
                return;
            }
            Wire.writeText(out, Handshake.ready());
            Log.i("net", "handshake complete, commands accepted");

            // ---- Command loop ----
            while (running.get() && !s.isClosed()) {
                Wire.Frame cmd = Wire.read(in);
                if (cmd.type != Wire.TYPE_TEXT) {
                    Log.d("net", "ignoring a non-text frame, type=" + cmd.type);
                    continue;
                }
                String line = cmd.text();
                if (line == null || line.isEmpty()) continue;
                if (!serve(line, out)) break;
            }
        } catch (Throwable t) {
            // Connection closed; a client-side close lands here and is normal
            Log.i("net", "connection closed after " + (System.currentTimeMillis() - t0) + "ms: " + t);
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String head(String s) {
        if (s == null) return "null";
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    /**
     * Pushes enumeration progress to the app as {@code #P {...}} text frames.
     *
     * <p>The {@code #P } prefix separates progress from results: the app's read loop treats
     * it as a callback rather than a return value, so progress needs no extra channel.
     */
    private static io.github.ryf5584.wxdbdump.payload.Progress progressWriter(BufferedOutputStream out) {
        return (done, total, stage) -> {
            try {
                Wire.writeText(out, "#P {\"done\":" + done + ",\"total\":" + total
                        + ",\"stage\":\"" + esc(stage) + "\"}");
            } catch (Throwable ignored) {
                // A failed progress push must not affect enumeration
            }
        };
    }

    /** Streams a file back in chunks; shared by FETCH (ciphertext) and DUMP (plaintext). */
    private static long streamFile(File f, BufferedOutputStream out) throws Exception {
        long sent = 0;
        FileInputStream fin = new FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = fin.read(buf)) > 0) {
                byte[] chunk = new byte[r];
                System.arraycopy(buf, 0, chunk, 0, r);
                Wire.writeBytes(out, chunk);
                sent += r;
            }
        } finally {
            fin.close();
        }
        return sent;
    }

    private static String baseName(String p) {
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** @return false when the connection should end. */
    private boolean serve(String line, BufferedOutputStream out) throws Exception {
        String[] parts = line.split(" ", 2);
        String op = parts[0];
        String arg = parts.length > 1 ? parts[1].trim() : "";

        // Log only the op name and duration; arguments hold paths that need not be echoed
        long t0 = System.currentTimeMillis();
        Log.i("net", "recv op=" + op + (arg.isEmpty() ? "" : " argLen=" + arg.length()));

        switch (op) {
            case "PING":
                Wire.writeText(out, "PONG");
                return true;

            case "STATUS": {
                StringBuilder sb = new StringBuilder();
                sb.append("{\"ok\":true");
                sb.append(",\"proc\":\"").append(esc(engine.host().processName())).append('"');
                sb.append(",\"pid\":").append(engine.host().pid());
                sb.append(",\"captured\":").append(engine.capture().capturedCount());
                sb.append(",\"verified\":").append(engine.capture().verifiedCount());
                sb.append(",\"dropped\":").append(engine.capture().droppedCount());
                sb.append(",\"vault\":").append(engine.vault().size());
                sb.append(",\"accounts\":").append(engine.vault().accountCount());
                sb.append('}');
                Wire.writeText(out, sb.toString());
                return true;
            }

            case "ACCOUNTS": {
                List<Account> accounts = engine.listAccounts(progressWriter(out));
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < accounts.size(); i++) {
                    Account a = accounts.get(i);
                    if (i > 0) sb.append(',');
                    sb.append("{\"id\":\"").append(a.dirName).append('"');
                    sb.append(",\"dir\":\"").append(esc(a.dir)).append('"');
                    sb.append(",\"uin\":").append(a.uin);
                    sb.append(",\"uinSource\":\"").append(esc(a.uinSource)).append('"');
                    sb.append(",\"wxid\":\"").append(esc(a.wxid)).append('"');
                    // Identity: nickname, alias, bound phone and whether a local avatar exists
                    sb.append(",\"nickname\":\"").append(esc(a.nickname)).append('"');
                    sb.append(",\"alias\":\"").append(esc(a.alias)).append('"');
                    sb.append(",\"mobile\":\"").append(esc(a.mobile)).append('"');
                    sb.append(",\"hasAvatar\":").append(a.avatarPath != null && !a.avatarPath.isEmpty());
                    sb.append(",\"dbCount\":").append(a.dbCount);
                    sb.append(",\"totalBytes\":").append(a.totalBytes);
                    sb.append(",\"decryptable\":").append(a.decryptable);
                    sb.append(",\"note\":\"").append(esc(a.note)).append('"');
                    sb.append('}');
                }
                sb.append(']');
                Wire.writeText(out, sb.toString());
                return true;
            }

            /** Sends an account avatar PNG; the app cannot read WeChat's private directory. */
            case "AVATAR": {
                List<Account> accounts = engine.listAccounts();
                String path = null;
                for (Account a : accounts) {
                    if (a.dirName.equals(arg) && a.avatarPath != null && !a.avatarPath.isEmpty()) {
                        path = a.avatarPath;
                        break;
                    }
                }
                if (path == null) {
                    Wire.writeText(out, "{\"ok\":false,\"reason\":\"no local avatar for this account\"}");
                    return true;
                }
                File f = new File(path);
                if (!f.exists() || !f.canRead()) {
                    Log.w("net", "AVATAR not readable: " + path);
                    Wire.writeText(out, "{\"ok\":false,\"reason\":\"avatar file not readable\"}");
                    return true;
                }
                Log.i("net", "AVATAR " + arg + " -> " + f.length() + " bytes");
                Wire.writeText(out, "{\"ok\":true,\"size\":" + f.length() + "}");
                long sent = streamFile(f, out);
                Wire.writeText(out, "END");
                Log.i("net", "AVATAR done, " + sent + " bytes");
                return true;
            }

            case "DBS": {
                List<Account> accounts = engine.listAccounts(progressWriter(out));
                for (Account a : accounts) {
                    if (!a.dirName.equals(arg)) continue;
                    // The account list is only lightly enriched, so enumeration must happen here instead of
                    // reading the cached field, which is always empty.
                    List<DbFile> dbs = DatabaseEnumerator.enumerate(
                            engine.host(), a, progressWriter(out));
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < dbs.size(); i++) {
                        DbFile d = dbs.get(i);
                        if (i > 0) sb.append(',');
                        sb.append("{\"name\":\"").append(esc(d.name)).append('"');
                        sb.append(",\"path\":\"").append(esc(d.path)).append('"');
                        sb.append(",\"size\":").append(d.sizeBytes);
                        sb.append(",\"encrypted\":").append(d.encrypted);
                        sb.append(",\"wal\":").append(d.hasWal);
                        sb.append(",\"shm\":").append(d.hasShm);
                        sb.append(",\"category\":\"").append(d.category.name()).append('"');
                        sb.append(",\"categoryKey\":\"")
                                .append(d.categoryKey == null ? "OTHER" : d.categoryKey).append('"');
                        // Purpose text, so the user knows what each database holds
                        sb.append(",\"desc\":\"")
                                .append(esc(d.description == null ? "" : d.description)).append('"');
                        sb.append(",\"hasKey\":").append(engine.vault().has(d.path));
                        sb.append('}');
                    }
                    sb.append(']');
                    Wire.writeText(out, sb.toString());
                    return true;
                }
                Wire.writeText(out, "[]");
                return true;
            }

            case "KEY": {
                // Account-level reuse: exact match first, then any key already verified for the account.
                // Every database of an account shares one key, so opening any single one is enough to
                // decrypt all of them.
                KeyVault.DbKey k = engine.vault().getForPath(arg);
                if (k == null) {
                    // A miss is worth noting: no key was captured for this account yet
                    Log.w("net", "KEY miss, no verified key for this account, path=" + arg
                            + " · " + engine.vault().describe()
                            + " · captured=" + engine.capture().capturedCount()
                            + " · verified=" + engine.capture().verifiedCount());
                    Wire.writeText(out, "{\"ok\":false,\"reason\":\"key not captured or not verified\"}");
                } else {
                    boolean reuse = !engine.vault().hasExact(arg);
                    Log.i("net", "KEY hit " + (reuse ? "(account reuse)" : "(exact)")
                            + " path=" + arg + " pageSize=" + k.pageSize
                            + " source=" + k.source + " verified=" + k.verified);
                    Wire.writeText(out, "{\"ok\":true,\"pageSize\":" + k.pageSize
                            + ",\"verified\":" + k.verified
                            + ",\"source\":\"" + esc(k.source) + (reuse ? "/account-reuse" : "") + "\""
                            + ",\"hex\":\"" + k.keyHex() + "\"}");
                }
                return true;
            }

            case "MD5": {
                // Lets the app verify the md5("mm"+uin) identity
                Wire.writeText(out, Digest.md5Hex(arg));
                return true;
            }

            case "FETCH": {
                File f = new File(arg);
                if (!f.exists() || !f.canRead()) {
                    Log.w("net", "FETCH failed, not readable, path=" + arg
                            + " exists=" + f.exists() + " canRead=" + f.canRead());
                    Wire.writeText(out, "{\"ok\":false,\"reason\":\"not readable\"}");
                    return true;
                }
                Log.i("net", "FETCH start path=" + arg + " size=" + f.length());
                Wire.writeText(out, "{\"ok\":true,\"size\":" + f.length() + "}");
                long sent = streamFile(f, out);
                Wire.writeText(out, "END");
                Log.i("net", "FETCH done path=" + arg + " " + sent + " bytes");
                return true;
            }

            /**
             * VERIFY checks whether a database decrypts, without transferring data.
             * It confirms in one round trip that every encrypted database of an account is readable.
             */
            case "VERIFY": {
                String cache = engine.host().cacheDir();
                if (cache == null) {
                    Wire.writeText(out, "{\"ok\":false,\"reason\":\"cache directory unavailable\"}");
                    return true;
                }
                File vdir = new File(cache, "wxdump-plain");
                if (!vdir.exists() && !vdir.mkdirs()) {
                    Wire.writeText(out, "{\"ok\":false,\"reason\":\"cannot create the staging directory\"}");
                    return true;
                }
                File vtmp = new File(vdir, "verify.db");
                try {
                    PlainDumper.Result r = engine.dumpPlain(arg, vtmp.getAbsolutePath());
                    if (r.ok) {
                        Wire.writeText(out, "{\"ok\":true,\"size\":" + r.size + ",\"plaintext\":true}");
                    } else {
                        Wire.writeText(out, "{\"ok\":false,\"reason\":\"" + esc(String.valueOf(r.reason)) + "\"}");
                    }
                    return true;
                } finally {
                    deleteQuietly(vtmp);
                    deleteQuietly(new File(vtmp.getAbsolutePath() + "-journal"));
                    deleteQuietly(new File(vtmp.getAbsolutePath() + "-wal"));
                    deleteQuietly(new File(vtmp.getAbsolutePath() + "-shm"));
                }
            }

            /**
             * DUMP decrypts a database with WeChat's own SQLCipher and streams the plaintext back.
             *
             * <p>The response matches FETCH exactly, so the app reuses one streaming reader; only the
             * payload differs, with plaintext=true in the head.
             */
            case "DUMP": {
                String cache = engine.host().cacheDir();
                if (cache == null) {
                    Wire.writeText(out, "{\"ok\":false,\"reason\":\"cache directory unavailable\"}");
                    return true;
                }
                File dir = new File(cache, "wxdump-plain");
                if (!dir.exists() && !dir.mkdirs()) {
                    Wire.writeText(out, "{\"ok\":false,\"reason\":\"cannot create the staging directory\"}");
                    return true;
                }
                File tmp = new File(dir, Math.abs(arg.hashCode()) + "-" + baseName(arg));
                long dumpStarted = System.currentTimeMillis();
                try {
                    PlainDumper.Result r = engine.dumpPlain(arg, tmp.getAbsolutePath());
                    if (!r.ok) {
                        Wire.writeText(out, "{\"ok\":false,\"reason\":\"" + esc(String.valueOf(r.reason)) + "\"}");
                        return true;
                    }
                    Log.i("net", "DUMP ok path=" + arg + " plainSize=" + r.size
                            + " decryptMs=" + (System.currentTimeMillis() - dumpStarted));
                    Wire.writeText(out, "{\"ok\":true,\"size\":" + r.size + ",\"plaintext\":true}");
                    long sent = streamFile(tmp, out);
                    Wire.writeText(out, "END");
                    Log.i("net", "DUMP stream finished, " + sent + " bytes");
                    return true;
                } finally {
                    // The plaintext temp file must be deleted; it holds chat data
                    try {
                        if (tmp.exists() && !tmp.delete()) {
                            Log.w("net", "failed to delete the plaintext temp file: " + tmp.getAbsolutePath());
                        }
                    } catch (Throwable ignored) {
                    }
                    // sqlcipher_export can leave journal or wal files behind
                    deleteQuietly(new File(tmp.getAbsolutePath() + "-journal"));
                    deleteQuietly(new File(tmp.getAbsolutePath() + "-wal"));
                    deleteQuietly(new File(tmp.getAbsolutePath() + "-shm"));
                }
            }

            case "BYE":
                return false;

            default:
                Wire.writeText(out, "{\"ok\":false,\"reason\":\"unknown op\"}");
                return true;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
