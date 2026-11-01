package com.sandevistan.client;

import com.sandevistan.Bolts;
import com.sandevistan.Phase;
import com.sandevistan.Sandevistan;
import com.sandevistan.TimeDilation;
import com.sandevistan.network.FxPayload;
import com.sandevistan.network.SyncPayload;
import com.sandevistan.network.TogglePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

public class SandevistanClient implements ClientModInitializer {
    public static KeyBinding TOGGLE_KEY;

    @Override
    public void onInitializeClient() {
        TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.sandevistan.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.sandevistan"));

        ClientPlayNetworking.registerGlobalReceiver(SyncPayload.ID, (payload, context) -> {
            Phase ph = Phase.of(payload.phase());
            if (ph == Phase.ACTIVE) {
                TimeDilation.ACTIVE.put(payload.player(),
                        TimeDilation.makeSource(payload.player(), payload.factor(), payload.radius(), payload.blacklist()));
            } else {
                TimeDilation.ACTIVE.remove(payload.player());
            }
            MinecraftClient mc = context.client();
            if (mc.player != null && mc.player.getUuid().equals(payload.player())) {
                ClientState.update(ph, payload.remaining(), payload.total());
            }
        });

        // Clarão de tela: 0 = acertou, 1 = desviou, 2 = matou.
        ClientPlayNetworking.registerGlobalReceiver(FxPayload.ID, (payload, context) -> {
            ClientState.flashType = payload.type();
            ClientState.flash = payload.type() == 2 ? 16 : payload.type() == 1 ? 10 : 8;
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            TimeDilation.ACTIVE.clear();
            ClientState.reset();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_KEY.wasPressed()) {
                if (client.player != null && client.currentScreen == null) {
                    ClientPlayNetworking.send(new TogglePayload());
                }
            }
            if (!client.isPaused()) ClientState.tick();
        });

        ClientTickEvents.END_WORLD_TICK.register(SandevistanClient::tickFx);
        HudRenderCallback.EVENT.register(SandevistanHud::render);
    }

    private static Vec3d randomDir(Random rng, double yScale) {
        Vec3d d = new Vec3d(rng.nextGaussian(), rng.nextGaussian() * yScale, rng.nextGaussian());
        return d.lengthSquared() < 1.0E-4 ? new Vec3d(1, 0, 0) : d.normalize();
    }

    /** Aura elétrica, raios em volta de quem usa, trilha de raios e faíscas nos mobs lentos. */
    private static void tickFx(ClientWorld world) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (TimeDilation.ACTIVE.isEmpty() || mc.isPaused()) return;
        Random rng = world.random;
        Bolts.Sink sink = (pe, x, y, z) -> world.addParticle(pe, x, y, z, 0, 0, 0);
        double k = Sandevistan.CONFIG.auraIntensity;

        for (UUID id : TimeDilation.ACTIVE.keySet()) {
            PlayerEntity p = world.getPlayerByUuid(id);
            if (p == null) continue;
            boolean local = mc.player != null && p == mc.player;

            // Faíscas nos pés.
            for (int i = 0; i < 3; i++) {
                world.addParticle(ParticleTypes.ELECTRIC_SPARK,
                        p.getX() + (rng.nextDouble() - 0.5) * 0.6, p.getY() + 0.05, p.getZ() + (rng.nextDouble() - 0.5) * 0.6,
                        (rng.nextDouble() - 0.5) * 0.12, 0.05 + rng.nextDouble() * 0.06, (rng.nextDouble() - 0.5) * 0.12);
            }

            double mx = p.getX() - p.prevX, my = p.getY() - p.prevY, mz = p.getZ() - p.prevZ;
            double hs = Math.sqrt(mx * mx + mz * mz);

            // Rastro de partículas contínuo.
            if (hs > 0.05 || Math.abs(my) > 0.05) {
                for (int s = 0; s < 4; s++) {
                    double t = s / 4.0;
                    double x = p.prevX + mx * t, y = p.prevY + my * t, z = p.prevZ + mz * t;
                    world.addParticle(ParticleTypes.END_ROD, x, y + 0.2, z, 0, 0, 0);
                    world.addParticle(ParticleTypes.SOUL_FIRE_FLAME, x, y + 0.9, z, 0, 0, 0);
                    if (rng.nextInt(2) == 0) world.addParticle(ParticleTypes.END_ROD, x, y + 1.6, z, 0, 0, 0);
                }
            }

            if (k <= 0) continue;
            Vec3d center = new Vec3d(p.getX(), p.getY() + 0.9, p.getZ());

            // Arcos de raio saindo do corpo.
            int arcs = (rng.nextDouble() < 0.55 * k ? 1 : 0) + (hs > 0.1 && rng.nextDouble() < 0.7 * k ? 1 : 0);
            for (int i = 0; i < arcs; i++) {
                Vec3d start = center.add((rng.nextDouble() - 0.5) * 0.5, (rng.nextDouble() - 0.5) * 1.2, (rng.nextDouble() - 0.5) * 0.5);
                Vec3d end = start.add(randomDir(rng, 0.7).multiply(1.2 + rng.nextDouble() * 1.8));
                Bolts.bolt(sink, rng, start, end, 0.16, 0.2, true);
            }

            // Raios rastejando pelo chão.
            if (rng.nextDouble() < 0.3 * k) {
                Vec3d dir = randomDir(rng, 0.0);
                Vec3d start = new Vec3d(p.getX(), p.getY() + 0.1, p.getZ());
                Bolts.bolt(sink, rng, start, start.add(dir.multiply(2.0 + rng.nextDouble() * 2.5)), 0.2, 0.22, false);
            }

            // Rabo de raios atrás de você quando corre.
            if (hs > 0.08 && rng.nextDouble() < 0.85 * k) {
                Vec3d back = new Vec3d(-mx, 0, -mz).normalize();
                Vec3d start = center.add((rng.nextDouble() - 0.5) * 0.4, (rng.nextDouble() - 0.5) * 1.0, (rng.nextDouble() - 0.5) * 0.4);
                Bolts.bolt(sink, rng, start, start.add(back.multiply(3.0 + rng.nextDouble() * 3.0)), 0.25, 0.25, true);
            }

            if (local) {
                // Tempestade ao ligar: raios caindo do céu em volta.
                if (ClientState.sinceActivation < 14) {
                    for (int i = 0; i < 4; i++) {
                        double ox = (rng.nextDouble() - 0.5) * 8.0, oz = (rng.nextDouble() - 0.5) * 8.0;
                        Vec3d top = new Vec3d(p.getX() + ox, p.getY() + 9.0, p.getZ() + oz);
                        Vec3d bottom = new Vec3d(p.getX() + ox * 0.6, p.getY(), p.getZ() + oz * 0.6);
                        Bolts.bolt(sink, rng, top, bottom, 0.4, 0.3, true);
                    }
                }
                // Estalos de eletricidade.
                if (rng.nextInt(10) == 0) {
                    world.playSound(p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT,
                            SoundCategory.PLAYERS, 0.06f, 1.9f + rng.nextFloat() * 0.3f, false);
                }
            }
        }

        // Tudo que está lento solta faíscas: o mundo "preso no tempo" fica carregado de eletricidade.
        if (mc.player != null && ClientState.phase == Phase.ACTIVE && k > 0 && world.getTime() % 3 == 0) {
            for (Entity e : world.getEntities()) {
                if (!(e instanceof LivingEntity) || e instanceof PlayerEntity) continue;
                if (e.squaredDistanceTo(mc.player) > 32 * 32) continue;
                if (rng.nextInt(3) != 0) continue;
                if (TimeDilation.factorFor(world, e) <= 1) continue;
                world.addParticle(ParticleTypes.ELECTRIC_SPARK, e.getParticleX(0.8), e.getRandomBodyY(), e.getParticleZ(0.8), 0, 0.01, 0);
                if (rng.nextInt(8) == 0) {
                    Vec3d c = new Vec3d(e.getX(), e.getBodyY(0.5), e.getZ());
                    Bolts.bolt(sink, rng, c, c.add(randomDir(rng, 1.0).multiply(0.8 + rng.nextDouble() * 0.8)), 0.1, 0.15, false);
                }
            }
        }
    }

    /** Percepção temporal: destaca (outline) o que está lento, só para quem ativou. */
    public static boolean shouldHighlight(Entity e) {
        if (!Sandevistan.CONFIG.highlightSlowedEntities || ClientState.phase != Phase.ACTIVE) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || e == mc.player) return false;
        if (!(e instanceof LivingEntity || e instanceof ProjectileEntity)) return false;
        return TimeDilation.factorFor(e.getWorld(), e) > 1;
    }
}
