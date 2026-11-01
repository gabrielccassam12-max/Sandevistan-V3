package com.sandevistan;

import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/** Desenha raios "zigue-zague" com partículas. Funciona no servidor e no cliente (via Sink). */
public final class Bolts {
    public interface Sink {
        void emit(ParticleEffect particle, double x, double y, double z);
    }

    private Bolts() {}

    /**
     * @param jitter  quanto o raio desvia da reta (em blocos)
     * @param spacing distância entre partículas
     * @param bright  true = núcleo branco (END_ROD) além das faíscas
     */
    public static void bolt(Sink sink, Random rng, Vec3d a, Vec3d b, double jitter, double spacing, boolean bright) {
        bolt(sink, rng, a, b, jitter, spacing, bright, true);
    }

    private static void bolt(Sink sink, Random rng, Vec3d a, Vec3d b, double jitter, double spacing, boolean bright, boolean allowBranch) {
        Vec3d d = b.subtract(a);
        double len = d.length();
        if (len < 0.1) return;
        Vec3d dir = d.multiply(1.0 / len);
        Vec3d helper = Math.abs(dir.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d u = dir.crossProduct(helper).normalize();
        Vec3d v = dir.crossProduct(u);

        int pts = Math.max(4, (int) (len / spacing));
        double ou = 0, ov = 0;
        double lim = jitter * 3.0;
        for (int i = 0; i <= pts; i++) {
            double t = i / (double) pts;
            ou = clamp(ou + (rng.nextDouble() - 0.5) * jitter * 2.0, lim);
            ov = clamp(ov + (rng.nextDouble() - 0.5) * jitter * 2.0, lim);
            double taper = Math.sin(Math.PI * t); // pontas coladas nos extremos
            Vec3d p = a.add(d.multiply(t)).add(u.multiply(ou * taper)).add(v.multiply(ov * taper));
            sink.emit(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z);
            if (bright && (i & 1) == 0) sink.emit(ParticleTypes.END_ROD, p.x, p.y, p.z);

            // Ramificação: um galho menor que sai do meio do raio.
            if (allowBranch && i > 1 && i < pts - 1 && len > 1.5 && rng.nextInt(Math.max(4, pts)) == 0) {
                Vec3d bd = new Vec3d(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian());
                if (bd.lengthSquared() > 1.0E-4) {
                    bd = bd.normalize().add(dir).normalize().multiply(len * (0.2 + rng.nextDouble() * 0.2));
                    bolt(sink, rng, p, p.add(bd), jitter * 0.7, spacing, false, false);
                }
            }
        }
    }

    private static double clamp(double v, double lim) {
        return Math.max(-lim, Math.min(lim, v));
    }
}
