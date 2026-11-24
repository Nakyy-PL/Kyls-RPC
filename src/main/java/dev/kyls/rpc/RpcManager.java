package dev.kyls.rpc;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;

public class RpcManager {
    private static final int STABLE_TICKS = 20;
    private static final long GAP_MODE_MS = 4000L;
    private static final long GAP_COUNT_MS = 15000L;
    private static final long RETRY_MS = 30000L;

    private final RpcConfig cfg;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "KylsRPC");
        t.setDaemon(true);
        return t;
    });

    private DiscordIpc ipc;
    private volatile boolean lastOk = true;
    private Snap candidate;
    private Snap sent;
    private int stable;
    private long lastSend;
    private String startKey = "";
    private long startEpoch = System.currentTimeMillis() / 1000L;

    public RpcManager(RpcConfig cfg) {
        this.cfg = cfg;
    }

    /** The only method that touches Minecraft classes. */
    private Snap read(Minecraft mc) {
        IntegratedServer singleplayer = mc.getSingleplayerServer();
        if (mc.level != null) {
            if (singleplayer != null) {
                String world = singleplayer.getWorldData().getLevelName();
                return new Snap(Mode.SOLO, world, 0);
            }
            ServerData data = mc.getCurrentServer();
            String address = data != null ? data.ip : "server";
            ClientPacketListener conn = mc.getConnection();
            int online = conn != null ? conn.getOnlinePlayers().size() : 0;
            return new Snap(Mode.MULTI, address, online);
        }
        Screen s = mc.screen;
        if (s instanceof JoinMultiplayerScreen) {
            return new Snap(Mode.BROWSER, "", 0);
        }
        if (s instanceof SelectWorldScreen || s instanceof CreateWorldScreen) {
            return new Snap(Mode.WORLD_SELECT, "", 0);
        }
        return new Snap(Mode.LOBBY, "", 0);
    }

    public void tick(Minecraft mc) {
        if (!cfg.enabled || cfg.appId.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!lastOk && now - lastSend > RETRY_MS) {
            sent = null;
        }
        Snap cur = read(mc);
        if (!cur.equals(candidate)) {
            candidate = cur;
            stable = 0;
            return;
        }
        if (stable < STABLE_TICKS) {
            stable++;
            return;
        }
        if (cur.equals(sent)) {
            return;
        }
        boolean modeChange = sent == null || !cur.key().equals(sent.key());
        if (now - lastSend < (modeChange ? GAP_MODE_MS : GAP_COUNT_MS)) {
            return;
        }
        if (!cur.key().equals(startKey)) {
            startKey = cur.key();
            startEpoch = now / 1000L;
        }
        push(cur, startEpoch);
        sent = cur;
        lastSend = now;
    }

    private String details(Snap s) {
        return switch (s.mode()) {
            case LOBBY -> "Idle in Main Menu";
            case BROWSER -> "Browsing Server List";
            case WORLD_SELECT -> "Selecting a World";
            case MULTI -> cfg.showServerAddress ? "Playing on " + s.target() : "Playing Multiplayer";
            case SOLO -> "Playing Singleplayer";
        };
    }

    private String state(Snap s) {
        return switch (s.mode()) {
            case LOBBY -> "Lobby \u2022 Waiting for mode selection";
            case BROWSER -> "Looking for a server";
            case WORLD_SELECT -> "Singleplayer";
            case MULTI -> s.online() > 0
                    ? "Online: " + String.format("%,d", s.online()) + " players"
                    : "Currently playing";
            case SOLO -> "World: " + s.target() + " \u2022 Solo Mode";
        };
    }

    private String activity(Snap s, long start) {
        String d = details(s);
        String st = state(s);
        // Discord requires at least 2 characters
        if (d.length() < 2) d += "  ";
        if (st.length() < 2) st += "  ";

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"details\":").append(DiscordIpc.quote(d));
        sb.append(",\"state\":").append(DiscordIpc.quote(st));
        sb.append(",\"timestamps\":{\"start\":").append(start).append("}");
        if (!cfg.image.isBlank()) {
            sb.append(",\"assets\":{\"large_image\":").append(DiscordIpc.quote(cfg.image))
              .append(",\"large_text\":").append(DiscordIpc.quote(cfg.name)).append("}");
        }
        return sb.append("}").toString();
    }

    private void push(Snap s, long start) {
        String act = activity(s, start);
        exec.submit(() -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (ipc == null || !ipc.connected) {
                        if (ipc != null) ipc.close();
                        ipc = new DiscordIpc(cfg.appId);
                        ipc.connect();
                    }
                    ipc.setActivity(act);
                    lastOk = true;
                    return;
                } catch (IOException e) {
                    if (ipc != null) ipc.close();
                    ipc = null;
                    if (attempt == 0 && !String.valueOf(e.getMessage()).startsWith("Discord")) {
                        continue;
                    }
                    lastOk = false;
                    KylsRpcMod.LOG.debug("RPC failed: {}", e.getMessage());
                    return;
                }
            }
        });
    }

    public void shutdown() {
        try {
            exec.submit(() -> {
                try {
                    if (ipc != null && ipc.connected) {
                        ipc.setActivity(null);
                    }
                } catch (IOException ignored) {
                }
                if (ipc != null) ipc.close();
            }).get(1500L, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
        exec.shutdownNow();
    }

    record Snap(Mode mode, String target, int online) {
        String key() {
            return mode + "|" + target;
        }
    }

    enum Mode {
        LOBBY, BROWSER, WORLD_SELECT, MULTI, SOLO
    }
}
