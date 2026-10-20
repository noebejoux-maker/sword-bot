package fr.swordbot;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

/**
 * Sauvegarde dans le monde le moment (en ticks) où chaque amélioration d'épée
 * sera débloquée. -1 = le succès n'a pas encore été obtenu.
 */
public class BotState extends PersistentState {
    public static final int STAGES = 4;

    public final long[] unlockAt = {-1L, -1L, -1L, -1L};

    private static final PersistentState.Type<BotState> TYPE =
            new PersistentState.Type<>(BotState::new, BotState::fromNbt, null);

    public static BotState get(MinecraftServer server) {
        PersistentStateManager manager = server.getOverworld().getPersistentStateManager();
        return manager.getOrCreate(TYPE, SwordBotMod.MOD_ID);
    }

    public static BotState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        BotState state = new BotState();
        long[] saved = nbt.getLongArray("unlockAt");
        for (int i = 0; i < Math.min(saved.length, STAGES); i++) {
            state.unlockAt[i] = saved[i];
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        nbt.putLongArray("unlockAt", unlockAt);
        return nbt;
    }
}
