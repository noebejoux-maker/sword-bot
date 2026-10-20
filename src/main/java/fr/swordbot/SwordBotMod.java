package fr.swordbot;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public class SwordBotMod implements ModInitializer {
    public static final String MOD_ID = "swordbot";

    private final ChaserManager manager = new ChaserManager();

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(manager::tick);
        ServerEntityEvents.ENTITY_LOAD.register(manager::onEntityLoad);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(manager::allowDamage);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> manager.reset());
    }
}
