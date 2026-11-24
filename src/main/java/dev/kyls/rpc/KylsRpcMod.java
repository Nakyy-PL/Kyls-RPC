package dev.kyls.rpc;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KylsRpcMod implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("KylsRPC");

    @Override
    public void onInitializeClient() {
        RpcConfig cfg = RpcConfig.load();
        RpcManager rpc = new RpcManager(cfg);
        ClientTickEvents.END_CLIENT_TICK.register(rpc::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> rpc.shutdown());
        LOG.info("Kyls Client RPC active (appId={})", cfg.appId);
    }
}
