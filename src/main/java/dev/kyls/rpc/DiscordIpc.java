package dev.kyls.rpc;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiscordIpc implements Closeable {
    public final String clientId;
    private InputStream in;
    private OutputStream out;
    private Closeable conn;
    public boolean connected;

    public DiscordIpc(String clientId) {
        this.clientId = clientId;
    }

    public synchronized void connect() throws IOException {
        IOException last = null;
        for (int i = 0; i < 10; i++) {
            try {
                open(i);
            } catch (IOException e) {
                last = e;
                closeQuiet();
                continue;
            }
            try {
                send(0, "{\"v\":1,\"client_id\":" + quote(clientId) + "}");
                Frame f = read();
                if (f.op() == 2 || f.json().contains("\"evt\":\"ERROR\"")) {
                    throw new IOException("Discord rejected the Application ID: " + errMsg(f.json()));
                }
                connected = true;
                return;
            } catch (IOException e) {
                closeQuiet();
                if (e.getMessage() != null && e.getMessage().startsWith("Discord rejected")) {
                    throw e;
                }
                last = e;
            }
        }
        throw new IOException("Discord not detected. Open the Discord desktop app first."
                + (last != null ? " (" + last.getMessage() + ")" : ""));
    }

    private void open(int i) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            final RandomAccessFile f = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
            conn = f;
            in = new InputStream() {
                @Override
                public int read() throws IOException {
                    return f.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return f.read(b, off, len);
                }
            };
            out = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    f.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    f.write(b, off, len);
                }
            };
            return;
        }

        List<String> dirs = new ArrayList<>();
        for (String key : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String v = System.getenv(key);
            if (v != null && !v.isEmpty()) {
                dirs.add(v);
            }
        }
        dirs.add("/tmp");
        String xdg = System.getenv("XDG_RUNTIME_DIR");
        if (xdg != null) {
            dirs.add(xdg + "/snap.discord");
            dirs.add(xdg + "/app/com.discordapp.Discord");
        }

        IOException last = new IOException("socket not found");
        for (String dir : dirs) {
            Path p = Paths.get(dir, "discord-ipc-" + i);
            if (!Files.exists(p)) {
                continue;
            }
            try {
                SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
                ch.connect(UnixDomainSocketAddress.of(p));
                conn = ch;
                in = Channels.newInputStream(ch);
                out = Channels.newOutputStream(ch);
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private void send(int op, String json) throws IOException {
        byte[] p = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + p.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(op).putInt(p.length).put(p);
        out.write(b.array());
        out.flush();
    }

    private Frame read() throws IOException {
        byte[] h = in.readNBytes(8);
        if (h.length < 8) {
            throw new EOFException("connection to Discord lost");
        }
        ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int op = hb.getInt();
        int len = hb.getInt();
        if (len < 0 || len > 0x100000) {
            throw new IOException("invalid frame");
        }
        byte[] p = in.readNBytes(len);
        if (p.length < len) {
            throw new EOFException("connection to Discord lost");
        }
        return new Frame(op, new String(p, StandardCharsets.UTF_8));
    }

    public synchronized void setActivity(String activityJson) throws IOException {
        long pid = ProcessHandle.current().pid();
        String args = "{\"pid\":" + pid
                + (activityJson != null ? ",\"activity\":" + activityJson : "") + "}";
        send(1, "{\"cmd\":\"SET_ACTIVITY\",\"args\":" + args
                + ",\"nonce\":\"" + UUID.randomUUID() + "\"}");
        Frame f = read();
        if (f.json().contains("\"evt\":\"ERROR\"")) {
            throw new IOException(errMsg(f.json()));
        }
    }

    private static String errMsg(String json) {
        Matcher m = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : json;
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private void closeQuiet() {
        connected = false;
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (IOException ignored) {
        }
        conn = null;
        in = null;
        out = null;
    }

    @Override
    public synchronized void close() {
        closeQuiet();
    }

    private record Frame(int op, String json) {
    }
}
