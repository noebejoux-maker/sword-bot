package fr.swordbot;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Gère le "Traqueur" : un zombie armé d'une épée qui poursuit les joueurs.
 * Tout est côté serveur et utilise une entité vanilla : aucun mod côté client n'est nécessaire.
 */
public class ChaserManager {

    // ------------------------------------------------------------------
    // RÉGLAGES (modifie ici si tu veux ajuster le comportement)
    // ------------------------------------------------------------------

    /** Tag de commande du bot (/kill @e[tag=swordbot_chaser] pour le supprimer). */
    static final String TAG = "swordbot_chaser";

    /** Succès qui débloquent chaque amélioration, dans l'ordre. */
    static final Identifier[] ADVANCEMENTS = {
            Identifier.of("minecraft", "story/mine_stone"),      // Stone Age
            Identifier.of("minecraft", "story/iron_tools"),      // Isn't It Iron Pick
            Identifier.of("minecraft", "story/shiny_gear"),      // Cover Me with Diamonds
            Identifier.of("minecraft", "nether/netherite_armor") // Cover Me in Debris
    };

    /** Délai (en secondes) entre l'obtention du succès et le cadeau de l'épée. */
    static final int[] DELAY_SECONDS = {20, 30, 60, 0};

    /** Épée tenue au stade 0 (départ), puis 1, 2, 3, 4. */
    static final Item[] SWORDS = {
            Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
            Items.DIAMOND_SWORD, Items.NETHERITE_SWORD
    };

    static final double MOVE_SPEED = 0.30;        // zombie vanilla : ~0.23
    static final double FOLLOW_RANGE = 64.0;      // distance à laquelle il repère un joueur
    static final int RESPAWN_TICKS = 200;         // réapparition 10 s après sa mort
    static final int STUCK_TICKS = 120;           // bloqué 6 s => il "passe" par les airs
    static final double FAR_DISTANCE = 48.0;      // au-delà : il se téléporte plus près au lieu de voler

    static final double RISE_SPEED = 0.15;        // blocs/tick pendant la montée (3 blocs/s)
    static final double CRUISE_SPEED = 0.22;      // blocs/tick pendant la traversée (~4,4 blocs/s)
    static final double DESCEND_SPEED = 0.12;     // blocs/tick pendant la descente
    static final double CRUISE_HEIGHT = 3.0;      // hauteur de vol au-dessus du point le plus haut

    // ------------------------------------------------------------------
    // ÉTAT
    // ------------------------------------------------------------------

    private ZombieEntity bot;
    private BotState state;
    private long tickCount;
    private long missingSince = -1;

    // détection "bloqué"
    private Vec3d anchorPos;
    private long anchorSince;

    // vol en douceur
    private boolean gliding;
    private int phase;          // 0 = montée, 1 = traversée, 2 = descente
    private Vec3d dest;
    private double cruiseY;
    private int glideTicks;
    private int blockedTicks;
    private long glideCooldownUntil;

    public void reset() {
        bot = null;
        state = null;
        gliding = false;
        missingSince = -1;
        anchorPos = null;
    }

    // ------------------------------------------------------------------
    // ÉVÉNEMENTS
    // ------------------------------------------------------------------

    public void tick(MinecraftServer server) {
        tickCount++;
        if (state == null) {
            state = BotState.get(server);
        }
        if (tickCount % 20 == 0) {
            slowTick(server);
        }
        if (isBotValid()) {
            fastTick();
        }
    }

    /** Évite les doublons après un redémarrage : un seul Traqueur existe à la fois. */
    public void onEntityLoad(Entity entity, ServerWorld world) {
        if (!(entity instanceof ZombieEntity zombie) || !zombie.getCommandTags().contains(TAG)) {
            return;
        }
        if (zombie == bot) {
            return;
        }
        if (isBotValid()) {
            zombie.discard();
            return;
        }
        // on "adopte" le Traqueur sauvegardé dans le monde
        zombie.setNoGravity(false);
        zombie.setAiDisabled(false);
        zombie.setInvulnerable(false);
        bot = zombie;
        gliding = false;
        missingSince = -1;
        anchorPos = null;
    }

    /** Invincible face à la lave, au feu, aux chutes et au vide. */
    public boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (bot == null || entity != bot) {
            return true;
        }
        return !(source.isOf(DamageTypes.LAVA)
                || source.isOf(DamageTypes.IN_FIRE)
                || source.isOf(DamageTypes.ON_FIRE)
                || source.isOf(DamageTypes.HOT_FLOOR)
                || source.isOf(DamageTypes.FALL)
                || source.isOf(DamageTypes.OUT_OF_WORLD)
                || source.isOf(DamageTypes.DROWN)
                || source.isOf(DamageTypes.IN_WALL)
                || source.isOf(DamageTypes.CRAMMING)
                || source.isOf(DamageTypes.FLY_INTO_WALL));
    }

    // ------------------------------------------------------------------
    // BOUCLE LENTE (1 fois par seconde)
    // ------------------------------------------------------------------

    private void slowTick(MinecraftServer server) {
        pollAdvancements(server);

        List<ServerPlayerEntity> players = eligiblePlayers(server);
        if (players.isEmpty()) {
            return;
        }

        if (!isBotValid()) {
            if (missingSince < 0) {
                missingSince = tickCount;
            } else if (tickCount - missingSince >= RESPAWN_TICKS) {
                spawnBot(server, players);
            }
            return;
        }
        missingSince = -1;

        ServerWorld world = (ServerWorld) bot.getWorld();
        updateSword(server);

        ServerPlayerEntity target = nearestPlayer(world, bot.getPos());
        if (target == null) {
            // plus aucun joueur dans cette dimension : il réapparaîtra près d'un joueur
            bot.discard();
            bot = null;
            gliding = false;
            return;
        }

        if (!gliding) {
            bot.setTarget(target);
            checkStuck(world, target);
        }
    }

    /** Détecte les succès (même fait par un seul joueur) et programme les cadeaux. */
    private void pollAdvancements(MinecraftServer server) {
        long now = server.getOverworld().getTime();
        List<ServerPlayerEntity> everyone = server.getPlayerManager().getPlayerList();
        for (int i = 0; i < BotState.STAGES; i++) {
            if (state.unlockAt[i] >= 0) {
                continue;
            }
            AdvancementEntry advancement = server.getAdvancementLoader().get(ADVANCEMENTS[i]);
            if (advancement == null) {
                continue;
            }
            for (ServerPlayerEntity player : everyone) {
                if (player.getAdvancementTracker().getProgress(advancement).isDone()) {
                    state.unlockAt[i] = now + DELAY_SECONDS[i] * 20L;
                    state.markDirty();
                    break;
                }
            }
        }
    }

    private int currentStage(MinecraftServer server) {
        long now = server.getOverworld().getTime();
        int stage = 0;
        for (int i = 0; i < BotState.STAGES; i++) {
            if (state.unlockAt[i] >= 0 && now >= state.unlockAt[i]) {
                stage = i + 1;
            }
        }
        return stage;
    }

    private void updateSword(MinecraftServer server) {
        Item wanted = SWORDS[currentStage(server)];
        if (!bot.getMainHandStack().isOf(wanted)) {
            bot.equipStack(EquipmentSlot.MAINHAND, new ItemStack(wanted));
            bot.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0.0f);
        }
    }

    /** S'il ne bouge plus alors qu'un joueur est loin, il passe par les airs (ou se rapproche). */
    private void checkStuck(ServerWorld world, ServerPlayerEntity target) {
        double dist = bot.distanceTo(target);
        if (anchorPos == null
                || dist <= 6.0
                || bot.getPos().squaredDistanceTo(anchorPos) > 2.25) {
            anchorPos = bot.getPos();
            anchorSince = tickCount;
            return;
        }
        if (tickCount - anchorSince < STUCK_TICKS) {
            return;
        }
        anchorPos = null;
        if (dist > FAR_DISTANCE) {
            relocateNear(world, target);
        } else {
            startGlide(world, target);
        }
    }

    private void relocateNear(ServerWorld world, ServerPlayerEntity target) {
        Vec3d spot = findSpawnSpot(world, target.getBlockPos(), 18, 26);
        if (spot == null) {
            return;
        }
        bot.refreshPositionAndAngles(spot.x, spot.y, spot.z, bot.getYaw(), 0.0f);
        bot.getNavigation().stop();
        bot.fallDistance = 0.0f;
    }

    // ------------------------------------------------------------------
    // APPARITION
    // ------------------------------------------------------------------

    private void spawnBot(MinecraftServer server, List<ServerPlayerEntity> players) {
        ServerPlayerEntity player = players.get(ThreadLocalRandom.current().nextInt(players.size()));
        ServerWorld world = player.getServerWorld();
        Vec3d spot = findSpawnSpot(world, player.getBlockPos(), 18, 26);
        if (spot == null) {
            return; // on réessaie à la seconde suivante
        }

        ZombieEntity zombie = new ZombieEntity(EntityType.ZOMBIE, world);
        zombie.refreshPositionAndAngles(spot.x, spot.y, spot.z,
                ThreadLocalRandom.current().nextFloat() * 360.0f, 0.0f);
        configure(zombie, currentStage(server));

        bot = zombie; // avant spawnEntity, pour que onEntityLoad le reconnaisse
        missingSince = -1;
        gliding = false;
        anchorPos = null;
        glideCooldownUntil = 0;
        world.spawnEntity(zombie);
    }

    private void configure(ZombieEntity zombie, int stage) {
        zombie.addCommandTag(TAG);
        zombie.setCustomName(Text.literal("Traqueur").formatted(Formatting.RED));
        zombie.setCustomNameVisible(true);
        zombie.setPersistent();
        zombie.setCanPickUpLoot(false);
        zombie.setBaby(false);

        setBase(zombie, EntityAttributes.GENERIC_FOLLOW_RANGE, FOLLOW_RANGE);
        setBase(zombie, EntityAttributes.GENERIC_MOVEMENT_SPEED, MOVE_SPEED);
        // les dégâts viennent de l'épée (comme un joueur) : bois 4, pierre 5, fer 6, diamant 7, netherite 8 (+1)
        setBase(zombie, EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0);
        setBase(zombie, EntityAttributes.ZOMBIE_SPAWN_REINFORCEMENTS, 0.0);

        zombie.addStatusEffect(new StatusEffectInstance(
                StatusEffects.FIRE_RESISTANCE, StatusEffectInstance.INFINITE, 0, false, false));

        // casque incassable : le soleil ne le fait pas brûler
        ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
        helmet.set(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(false));
        helmet.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(0x1B1B1B, false));
        zombie.equipStack(EquipmentSlot.HEAD, helmet);
        zombie.setEquipmentDropChance(EquipmentSlot.HEAD, 0.0f);

        zombie.equipStack(EquipmentSlot.MAINHAND, new ItemStack(SWORDS[stage]));
        zombie.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0.0f);
    }

    private static void setBase(LivingEntity entity, RegistryEntry<EntityAttribute> attribute, double value) {
        EntityAttributeInstance instance = entity.getAttributeInstance(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    // ------------------------------------------------------------------
    // BOUCLE RAPIDE (chaque tick, seulement pour le bot)
    // ------------------------------------------------------------------

    private void fastTick() {
        ServerWorld world = (ServerWorld) bot.getWorld();
        bot.setFireTicks(0);

        if (gliding) {
            glideStep(world);
            return;
        }
        if (tickCount >= glideCooldownUntil && inDanger(world)) {
            startGlide(world, nearestPlayer(world, bot.getPos()));
        }
    }

    /** Vrai s'il est dans la lave, sous le monde, en train de tomber dans un trou, ou sur le point d'y marcher. */
    private boolean inDanger(ServerWorld world) {
        if (bot.getY() < world.getBottomY() + 3) {
            return true;
        }
        if (bot.isInLava()) {
            return true;
        }
        Vec3d velocity = bot.getVelocity();
        if (!bot.isOnGround()) {
            return velocity.y < -0.1 && columnHazard(world, bot.getBlockPos());
        }
        double h2 = velocity.x * velocity.x + velocity.z * velocity.z;
        if (h2 > 0.0004) {
            double len = Math.sqrt(h2);
            for (double k : new double[] {1.2, 2.4}) {
                Vec3d ahead = bot.getPos().add(velocity.x / len * k, 0, velocity.z / len * k);
                if (columnHazard(world, BlockPos.ofFloored(ahead))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Colonne dangereuse : de la lave, ou aucun sol dans les 8 blocs en dessous. */
    private static boolean columnHazard(ServerWorld world, BlockPos pos) {
        for (int dy = 0; dy <= 8; dy++) {
            BlockPos p = pos.down(dy);
            if (!world.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
                return false;
            }
            BlockState s = world.getBlockState(p);
            if (s.getFluidState().isIn(FluidTags.LAVA)) {
                return true;
            }
            if (!s.getCollisionShape(world, p).isEmpty() || !s.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // VOL EN DOUCEUR : monter, traverser, redescendre
    // ------------------------------------------------------------------

    private void startGlide(ServerWorld world, ServerPlayerEntity target) {
        if (gliding || tickCount < glideCooldownUntil) {
            return;
        }
        Vec3d landing = null;
        if (target != null) {
            landing = findSafeLanding(world, target.getBlockPos(), 2, 8, 6);
        }
        if (landing == null) {
            landing = findSafeLanding(world, bot.getBlockPos(), 0, 12, 12);
        }
        if (landing == null) {
            landing = findSafeLanding(world, world.getSpawnPos(), 0, 16, 24);
        }
        if (landing == null) {
            return;
        }

        gliding = true;
        phase = 0;
        dest = landing;
        glideTicks = 0;
        blockedTicks = 0;
        cruiseY = Math.min(Math.max(bot.getY(), landing.y) + CRUISE_HEIGHT, world.getTopY() - 2);

        bot.setNoGravity(true);
        bot.setAiDisabled(true);
        bot.setInvulnerable(true);
        bot.getNavigation().stop();
        bot.setTarget(null);
        bot.setVelocity(Vec3d.ZERO);
        bot.fallDistance = 0.0f;
    }

    private void glideStep(ServerWorld world) {
        glideTicks++;
        bot.fallDistance = 0.0f;
        bot.setVelocity(Vec3d.ZERO);

        Vec3d pos = bot.getPos();
        double dx = dest.x - pos.x;
        double dz = dest.z - pos.z;
        double hd = Math.sqrt(dx * dx + dz * dz);
        Vec3d move = Vec3d.ZERO;

        if (phase == 0) { // monter bien au-dessus du trou / de la lave
            double dy = cruiseY - pos.y;
            if (dy <= 0.05) {
                phase = 1;
            } else {
                move = new Vec3d(0, Math.min(RISE_SPEED, dy), 0);
            }
        }
        if (phase == 1) { // traverser lentement vers les joueurs
            if (hd <= 0.5) {
                phase = 2;
            } else {
                double step = Math.min(CRUISE_SPEED, hd);
                move = new Vec3d(dx / hd * step, 0, dz / hd * step);
            }
        }
        if (phase == 2) { // se poser sur un sol sûr
            double dy = dest.y - pos.y;
            if (hd <= 0.3 && (dy >= -0.05 || bot.isOnGround())) {
                finishGlide();
                return;
            }
            double step = Math.min(0.1, hd);
            double mx = hd > 0.05 ? dx / hd * step : 0;
            double mz = hd > 0.05 ? dz / hd * step : 0;
            move = new Vec3d(mx, dy < 0 ? Math.max(dy, -DESCEND_SPEED) : 0, mz);
        }

        if (move.lengthSquared() > 1.0E-6) {
            Vec3d before = bot.getPos();
            bot.move(MovementType.SELF, move);
            double moved = bot.getPos().distanceTo(before);
            if (moved < move.length() * 0.3) {
                blockedTicks++;
                if (phase == 0) {          // plafond : on avance à cette hauteur
                    phase = 1;
                    cruiseY = bot.getY();
                } else if (phase == 1 && blockedTicks % 5 == 0) { // mur : on monte plus haut
                    cruiseY = Math.min(cruiseY + 2.0, world.getTopY() - 2);
                    phase = 0;
                }
            } else {
                blockedTicks = Math.max(0, blockedTicks - 1);
            }
            if (hd > 0.05) {
                float yaw = (float) (MathHelper.atan2(dz, dx) * 57.29577951308232) - 90.0f;
                bot.setYaw(yaw);
                bot.setHeadYaw(yaw);
                bot.setBodyYaw(yaw);
            }
        }

        // sécurité : si le vol dure trop ou reste bloqué, on le dépose directement
        if (blockedTicks > 40 || glideTicks > 20 * 40) {
            bot.refreshPositionAndAngles(dest.x, dest.y, dest.z, bot.getYaw(), 0.0f);
            finishGlide();
            return;
        }

        if (glideTicks % 4 == 0) {
            world.spawnParticles(ParticleTypes.CLOUD, bot.getX(), bot.getY() + 0.1, bot.getZ(),
                    3, 0.25, 0.05, 0.25, 0.01);
        }
    }

    private void finishGlide() {
        gliding = false;
        bot.setNoGravity(false);
        bot.setAiDisabled(false);
        bot.setInvulnerable(false);
        bot.setVelocity(Vec3d.ZERO);
        bot.fallDistance = 0.0f;
        glideCooldownUntil = tickCount + 60;
        anchorPos = null;
    }

    // ------------------------------------------------------------------
    // RECHERCHE DE POSITIONS
    // ------------------------------------------------------------------

    private static boolean isEligible(ServerPlayerEntity player) {
        return player.isAlive() && !player.isSpectator() && !player.isCreative();
    }

    private static List<ServerPlayerEntity> eligiblePlayers(MinecraftServer server) {
        return server.getPlayerManager().getPlayerList().stream()
                .filter(ChaserManager::isEligible)
                .toList();
    }

    private static ServerPlayerEntity nearestPlayer(ServerWorld world, Vec3d from) {
        ServerPlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!isEligible(player)) {
                continue;
            }
            double d = player.squaredDistanceTo(from);
            if (d < bestDist) {
                bestDist = d;
                best = player;
            }
        }
        return best;
    }

    private static boolean canStand(ServerWorld world, BlockPos pos) {
        BlockPos below = pos.down();
        BlockState floor = world.getBlockState(below);
        if (!floor.isSideSolidFullSquare(world, below, Direction.UP) || floor.isOf(Blocks.MAGMA_BLOCK)) {
            return false;
        }
        return isFree(world, pos) && isFree(world, pos.up());
    }

    private static boolean isFree(ServerWorld world, BlockPos pos) {
        BlockState s = world.getBlockState(pos);
        return s.getCollisionShape(world, pos).isEmpty()
                && s.getFluidState().isEmpty()
                && !s.isIn(BlockTags.FIRE);
    }

    /** Premier endroit où se tenir dans cette colonne, en partant de la hauteur cy puis en s'éloignant. */
    private static Vec3d findStandInColumn(ServerWorld world, int x, int z, int cy, int vRange) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int k = 0; k <= vRange; k++) {
            for (int s = (k == 0 ? 0 : -1); s <= 1; s += 2) {
                m.set(x, cy + k * s, z);
                if (canStand(world, m)) {
                    return new Vec3d(x + 0.5, m.getY(), z + 0.5);
                }
            }
        }
        return null;
    }

    /** Point d'apparition au hasard autour d'un joueur, entre minR et maxR blocs. */
    private static Vec3d findSpawnSpot(ServerWorld world, BlockPos center, int minR, int maxR) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 40; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = minR + random.nextDouble() * (maxR - minR);
            int x = center.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * distance);
            if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Vec3d spot = findStandInColumn(world, x, z, center.getY(), 10);
            if (spot != null) {
                return spot;
            }
        }
        return null;
    }

    /** Sol sûr (ni lave, ni feu, ni vide) le plus proche de center, à au moins minR blocs. */
    private static Vec3d findSafeLanding(ServerWorld world, BlockPos center, int minR, int maxR, int vRange) {
        Vec3d best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -maxR; dx <= maxR; dx++) {
            for (int dz = -maxR; dz <= maxR; dz++) {
                int h2 = dx * dx + dz * dz;
                if (h2 < minR * minR || h2 > maxR * maxR) {
                    continue;
                }
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                Vec3d spot = findStandInColumn(world, x, z, center.getY(), vRange);
                if (spot == null) {
                    continue;
                }
                double dy = spot.y - center.getY();
                double score = h2 + dy * dy;
                if (score < bestScore) {
                    bestScore = score;
                    best = spot;
                }
            }
        }
        return best;
    }

    private boolean isBotValid() {
        return bot != null && bot.isAlive() && !bot.isRemoved();
    }
}
