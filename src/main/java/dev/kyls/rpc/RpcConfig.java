package dev.kyls.rpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

public class RpcConfig {
    public String appId = "1559824929816322058";
    public String name = "Kyls Client by NakyyTzy";
    public String image = "";
    public boolean showServerAddress = true;
    public boolean enabled = true;

    public static RpcConfig load() {
        RpcConfig c = new RpcConfig();
        Path file = FabricLoader.getInstance().getConfigDir().resolve("kyls_client_rpc.properties");
        Properties p = new Properties();

        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                KylsRpcMod.LOG.warn("Failed to read config: {}", e.getMessage());
            }
        }

        c.appId = p.getProperty("appId", c.appId).trim();
        c.name = p.getProperty("name", c.name).trim();
        c.image = p.getProperty("image", c.image).trim();
        c.showServerAddress = Boolean.parseBoolean(p.getProperty("showServerAddress", "true"));
        c.enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));

        p.setProperty("appId", c.appId);
        p.setProperty("name", c.name);
        p.setProperty("image", c.image);
        p.setProperty("showServerAddress", String.valueOf(c.showServerAddress));
        p.setProperty("enabled", String.valueOf(c.enabled));

        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(new OutputStreamWriter(out, StandardCharsets.UTF_8), "Kyls Client RPC");
        } catch (IOException e) {
            KylsRpcMod.LOG.warn("Failed to write config: {}", e.getMessage());
        }
        return c;
    }
}
