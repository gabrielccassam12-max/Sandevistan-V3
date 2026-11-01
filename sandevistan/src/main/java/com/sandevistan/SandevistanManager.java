package com.sandevistan;

import com.sandevistan.network.FxPayload;
import com.sandevistan.network.SyncPayload;
import com.sandevistan.network.TogglePayload;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lógica autoritativa (servidor): fases, buffs, combate e efeitos de raio. */
public final class SandevistanManager {
    private static final class State {
        Phase phase = Phase.READY;
        int remaining;
        int total; // 0 + ACTIVE = duração infinita
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static MinecraftServer server;
    /** Evita que o dano do raio em cadeia dispare outro raio em cadeia. */
    private static boolean chaining = false;

    private SandevistanManager() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            server = null;
            STATES.clear();
            TimeDilation.ACTIVE.clear();
        });

        ServerTickEvents.END_SERVER_TICK.register(SandevistanManager::tick);

        ServerPlayNetworking.registerGlobalReceiver(TogglePayload.ID, (payload, context) ->
                context.server().execute(() -> toggle(context.player())));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            for (Map.Entry<UUID, State> e : STATES.entrySet()) {
                if (e.getValue().phase == Phase.ACTIVE) {
                    ServerPlayNetworking.send(handler.player, payload(e.getKey(), e.getValue()));
                }
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> reset(handler.player.getUuid()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> reset(newPlayer.getUuid()));

        // Reflexos: desvia de projéteis com um clarão elétrico.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity p
                    && isActive(p.getUuid())
                    && source.getSource() instanceof ProjectileEntity
                    && p.getRandom().nextDouble() < Sandevistan.CONFIG.autoDodgeChance
                    && p.getWorld() instanceof ServerWorld sw) {
                Vec3d c = new Vec3d(p.getX(), p.getBodyY(0.5), p.getZ());
                sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 25, 0.4, 0.6, 0.4, 0.4);
                burst(sw, c, 4, 1.2, 2.5);
                sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ENDERMAN_TELEPORT,
                        SoundCategory.PLAYERS, 0.6f, 2.0f);
                ServerPlayNetworking.send(p, new FxPayload(1));
                return false;
            }
            return true;
        });

        // Impacto + raio em cadeia quando você acerta algo.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
            if (chaining || damage <= 0) return;
            if (source.getAttacker() instanceof ServerPlayerEntity attacker
                    && attacker != entity
                    && isActive(attacker.getUuid())
                    && entity.getWorld() instanceof ServerWorld sw) {
                Vec3d c = new Vec3d(entity.getX(), entity.getBodyY(0.5), entity.getZ());
                sw.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
                sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 30, 0.3, 0.4, 0.3, 0.6);
                sw.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 15, 0.3, 0.4, 0.3, 0.5);
                burst(sw, c, 5, 1.2, 2.6);
                sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.35f, 2.0f);
                ServerPlayNetworking.send(attacker, new FxPayload(0));
                chain(sw, attacker, entity, c);
            }
        });

        // Raio de verdade (cosmético) em cada abate.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (Sandevistan.CONFIG.lightningOnKill
                    && source.getAttacker() instanceof ServerPlayerEntity attacker
                    && attacker != entity
                    && isActive(attacker.getUuid())
                    && entity.getWorld() instanceof ServerWorld sw) {
                strike(sw, entity.getX(), entity.getY(), entity.getZ());
                ServerPlayNetworking.send(attacker, new FxPayload(2));
            }
        });
    }

    public static boolean isActive(UUID id) {
        State s = STATES.get(id);
        return s != null && s.phase == Phase.ACTIVE;
    }

    // ---------------------------------------------------------------- efeitos

    private static void strike(ServerWorld sw, double x, double y, double z) {
        LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, sw);
        bolt.refreshPositionAfterTeleport(x, y, z);
        bolt.setCosmetic(true); // sem dano e sem fogo
        sw.spawnEntity(bolt);
    }

    /** Raios saindo em todas as direções a partir de um ponto. */
    private static void burst(ServerWorld sw, Vec3d c, int n, double minLen, double maxLen) {
        Bolts.Sink sink = (pe, x, y, z) -> sw.spawnParticles(pe, x, y, z, 1, 0, 0, 0, 0);
        for (int i = 0; i < n; i++) {
            Vec3d dir = new Vec3d(sw.random.nextGaussian(), sw.random.nextGaussian(), sw.random.nextGaussian());
            if (dir.lengthSquared() < 1.0E-4) continue;
            double len = minLen + sw.random.nextDouble() * (maxLen - minLen);
            Bolts.bolt(sink, sw.random, c, c.add(dir.normalize().multiply(len)), 0.18, 0.3, true);
        }
    }

    /** Raio em cadeia: do alvo acertado para monstros próximos. */
    private static void chain(ServerWorld sw, ServerPlayerEntity attacker, LivingEntity origin, Vec3d from) {
        SandevistanConfig c = Sandevistan.CONFIG;
        if (c.chainLightningDamage <= 0 || c.chainLightningTargets <= 0) return;
        List<LivingEntity> near = sw.getEntitiesByClass(LivingEntity.class,
                origin.getBoundingBox().expand(c.chainLightningRange),
                e -> e != origin && e != attacker && e.isAlive() && e instanceof Monster);
        near.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(origin)));
        Bolts.Sink sink = (pe, x, y, z) -> sw.spawnParticles(pe, x, y, z, 1, 0, 0, 0, 0);
        chaining = true;
        try {
            int n = Math.min(c.chainLightningTargets, near.size());
            for (int i = 0; i < n; i++) {
                LivingEntity t = near.get(i);
                Vec3d to = new Vec3d(t.getX(), t.getBodyY(0.5), t.getZ());
                Bolts.bolt(sink, sw.random, from, to, 0.22, 0.25, true);
                sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, to.x, to.y, to.z, 15, 0.25, 0.35, 0.25, 0.4);
                t.damage(sw.getDamageSources().indirectMagic(attacker, attacker), (float) c.chainLightningDamage);
            }
        } finally {
            chaining = false;
        }
    }

    private static void activationFx(ServerWorld sw, ServerPlayerEntity p) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        sw.spawnParticles(ParticleTypes.SONIC_BOOM, x, y + 1.0, z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.FLASH, x, y + 1.0, z, 1, 0, 0, 0, 0);
        // Duas ondas de choque de faíscas saindo dos pés (count 0 = offsets viram velocidade).
        for (int ring = 0; ring < 2; ring++) {
            for (int i = 0; i < 40; i++) {
                double a = i * Math.PI * 2.0 / 40.0;
                sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, x, y + 0.15, z, 0,
                        Math.cos(a), 0.03, Math.sin(a), 0.6 + 0.5 * ring);
            }
        }
        burst(sw, new Vec3d(x, y + 1.0, z), 10, 2.0, 4.5);
        if (Sandevistan.CONFIG.lightningOnActivate) strike(sw, x, y, z);
    }

    private static void deactivationFx(ServerWorld sw, ServerPlayerEntity p) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        // Implosão: faíscas nascem longe e são puxadas para você.
        for (int i = 0; i < 32; i++) {
            double a = i * Math.PI * 2.0 / 32.0;
            double cx = Math.cos(a), cz = Math.sin(a);
            sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, x + cx * 3.0, y + 0.6, z + cz * 3.0, 0, -cx, 0.0, -cz, 0.5);
        }
        burst(sw, new Vec3d(x, y + 1.0, z), 5, 1.5, 3.0);
    }

    // ---------------------------------------------------------------- ciclo de vida

    private static void toggle(ServerPlayerEntity p) {
        State s = STATES.computeIfAbsent(p.getUuid(), k -> new State());
        switch (s.phase) {
            case READY -> activate(p, s);
            case ACTIVE -> deactivate(p, s);
            case COOLDOWN -> p.sendMessage(Text.translatable("message.sandevistan.cooldown"), true);
        }
    }

    private static void tick(MinecraftServer srv) {
        Iterator<Map.Entry<UUID, State>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, State> e = it.next();
            State s = e.getValue();
            ServerPlayerEntity p = srv.getPlayerManager().getPlayer(e.getKey());
            if (p == null) {
                TimeDilation.ACTIVE.remove(e.getKey());
                it.remove();
                continue;
            }
            if (s.phase == Phase.ACTIVE) {
                if (!p.isAlive()) {
                    deactivate(p, s);
                } else if (s.total > 0 && --s.remaining <= 0) {
                    deactivate(p, s);
                }
            } else if (s.phase == Phase.COOLDOWN) {
                if (--s.remaining <= 0) {
                    s.phase = Phase.READY;
                    s.remaining = 0;
                    broadcast(e.getKey(), s);
                }
            }
        }
    }

    private static void activate(ServerPlayerEntity p, State s) {
        SandevistanConfig c = Sandevistan.CONFIG;
        s.phase = Phase.ACTIVE;
        s.total = s.remaining = c.durationSeconds <= 0 ? 0 : Math.max(1, (int) Math.round(c.durationSeconds * 20.0));

        TimeDilation.ACTIVE.put(p.getUuid(),
                TimeDilation.makeSource(p.getUuid(), c.slowdownFactor(), c.affectRadius, String.join(",", c.entityBlacklist)));
        applyBuffs(p, c);

        if (p.getWorld() instanceof ServerWorld sw) {
            sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 2.0f);
            sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_CONDUIT_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.9f);
            activationFx(sw, p);
        }
        broadcast(p.getUuid(), s);
    }

    private static void deactivate(ServerPlayerEntity p, State s) {
        SandevistanConfig c = Sandevistan.CONFIG;
        removeBuffs(p);
        TimeDilation.ACTIVE.remove(p.getUuid());

        int cd = (int) Math.round(c.cooldownSeconds * 20.0);
        if (cd > 0) {
            s.phase = Phase.COOLDOWN;
            s.total = s.remaining = cd;
        } else {
            s.phase = Phase.READY;
            s.total = s.remaining = 0;
        }
        if (p.isAlive() && p.getWorld() instanceof ServerWorld sw) {
            sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.6f);
            sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_CONDUIT_DEACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.5f);
            deactivationFx(sw, p);
        }
        broadcast(p.getUuid(), s);
    }

    private static void reset(UUID id) {
        State removed = STATES.remove(id);
        TimeDilation.ACTIVE.remove(id);
        if (removed != null) broadcast(id, new State());
    }

    // ---------------------------------------------------------------- buffs

    private static void applyBuffs(ServerPlayerEntity p, SandevistanConfig c) {
        addMod(p, EntityAttributes.GENERIC_MOVEMENT_SPEED, "speed", c.speedMultiplier - 1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        addMod(p, EntityAttributes.GENERIC_JUMP_STRENGTH, "jump", c.jumpMultiplier - 1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        addMod(p, EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, "fall", c.extraSafeFallDistance, EntityAttributeModifier.Operation.ADD_VALUE);
        addMod(p, EntityAttributes.GENERIC_ATTACK_SPEED, "attack", c.attackSpeedMultiplier - 1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    }

    private static void removeBuffs(ServerPlayerEntity p) {
        for (RegistryEntry<EntityAttribute> a : new RegistryEntry[]{
                EntityAttributes.GENERIC_MOVEMENT_SPEED, EntityAttributes.GENERIC_JUMP_STRENGTH,
                EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, EntityAttributes.GENERIC_ATTACK_SPEED}) {
            EntityAttributeInstance inst = p.getAttributeInstance(a);
            if (inst == null) continue;
            for (String n : new String[]{"speed", "jump", "fall", "attack"}) {
                inst.removeModifier(Sandevistan.id(n));
            }
        }
    }

    private static void addMod(ServerPlayerEntity p, RegistryEntry<EntityAttribute> attr, String name,
                               double value, EntityAttributeModifier.Operation op) {
        EntityAttributeInstance inst = p.getAttributeInstance(attr);
        if (inst == null) return;
        Identifier id = Sandevistan.id(name);
        inst.removeModifier(id);
        if (value == 0.0) return; // buff desligado na config
        inst.addTemporaryModifier(new EntityAttributeModifier(id, value, op));
    }

    // ---------------------------------------------------------------- rede

    private static SyncPayload payload(UUID id, State s) {
        SandevistanConfig c = Sandevistan.CONFIG;
        return new SyncPayload(id, s.phase.ordinal(), s.remaining, s.total,
                c.slowdownFactor(), c.affectRadius, String.join(",", c.entityBlacklist));
    }

    private static void broadcast(UUID id, State s) {
        if (server == null) return;
        SyncPayload pl = payload(id, s);
        for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(other, pl);
        }
    }
}
