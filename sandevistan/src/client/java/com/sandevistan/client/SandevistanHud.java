package com.sandevistan.client;

import com.sandevistan.Phase;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.Random;

/** Efeitos de tela (tinta, vinheta, linhas de velocidade, glitch, animação de ativação) e barra de energia. */
public final class SandevistanHud {
    private static final int CYAN = 0x00E5FF;
    private static final int YELLOW = 0xFFD500;

    private SandevistanHud() {}

    public static void render(DrawContext ctx, RenderTickCounter counter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();

        if (ClientState.phase == Phase.ACTIVE) {
            renderEffects(ctx, mc, w, h);
        }
        renderBar(ctx, mc, w, h);
    }

    private static void renderEffects(DrawContext ctx, MinecraftClient mc, int w, int h) {
        long ms = Util.getMeasuringTimeMs();
        float pulse = 0.5f + 0.5f * (float) Math.sin(ms / 180.0);
        int t = ClientState.sinceActivation;

        // Tinta azulada que pulsa suavemente.
        ctx.fill(0, 0, w, h, argb(22 + (int) (14 * pulse), CYAN));

        // Vinheta nas bordas, deslizando de ciano para amarelo.
        int edge = lerpColor(CYAN, YELLOW, 0.15f + 0.35f * pulse);
        int thickV = Math.max(12, h / 6), thickH = Math.max(12, w / 8);
        ctx.fillGradient(0, 0, w, thickV, argb(150, edge), argb(0, edge));
        ctx.fillGradient(0, h - thickV, w, h, argb(0, edge), argb(150, edge));
        int steps = 24;
        for (int i = 0; i < steps; i++) {
            float k = 1f - i / (float) steps;
            int a = (int) (130 * k * k);
            int x0 = i * thickH / steps, x1 = (i + 1) * thickH / steps;
            if (x1 <= x0) x1 = x0 + 1;
            ctx.fill(x0, 0, x1, h, argb(a, edge));
            ctx.fill(w - x1, 0, w - x0, h, argb(a, edge));
        }

        // Glitch sutil: barras horizontais deslocadas que piscam.
        Random g = new Random(ms / 90);
        if (g.nextInt(3) == 0) {
            for (int i = 0; i < 2; i++) {
                int y = g.nextInt(Math.max(1, h - 4));
                int off = g.nextInt(7) - 3;
                ctx.fill(off, y, w + off, y + 1 + g.nextInt(3), argb(28, g.nextBoolean() ? CYAN : 0xFF2D95));
            }
        }

        // Clarão de acerto / desvio / abate.
        if (ClientState.flash > 0) {
            int a = Math.min(150, ClientState.flash * 16);
            ctx.fill(0, 0, w, h, argb(a, ClientState.flashType == 1 ? CYAN : 0xE6FBFF));
        }

        // Raios na tela.
        renderBolts(ctx, w, h, ms, t);

        // Linhas de velocidade: intensidade depende da velocidade horizontal real.
        double speed = mc.player.getVelocity().horizontalLength();
        float intensity = (float) Math.min(1.0, speed / 0.35);
        if (intensity > 0.05f) {
            Random r = new Random(ms / 45);
            int lines = 6 + (int) (36 * intensity);
            for (int i = 0; i < lines; i++) {
                boolean left = r.nextBoolean();
                int y = r.nextInt(h);
                int len = 20 + r.nextInt(70);
                int inset = r.nextInt(Math.max(1, w / 4));
                int x = left ? inset : w - inset - len;
                ctx.fill(x, y, x + len, y + 1, argb(40 + r.nextInt(70), 0xFFFFFF));
            }
        }

        // Sequência de ativação: flash, varredura e texto "implante ligando".
        if (t < 60) {
            int flash = Math.max(0, 210 - t * 12);
            if (flash > 0) ctx.fill(0, 0, w, h, argb(flash, 0xE6FBFF));
            if (t < 24) {
                int y = (int) (h * (t / 24f));
                ctx.fill(0, y, w, y + 3, argb(200, YELLOW));
                ctx.fill(0, y + 3, w, y + 18, argb(40, CYAN));
            }
            int alpha = t < 40 ? 255 : Math.max(5, 255 - (t - 40) * 12);
            Random j = new Random(ms / 60);
            float jitter = t < 14 ? (j.nextFloat() - 0.5f) * 8f : 0f;
            MatrixStack m = ctx.getMatrices();
            m.push();
            m.translate(w / 2f + jitter, h * 0.27f, 0);
            m.scale(2.2f, 2.2f, 1f);
            ctx.drawCenteredTextWithShadow(mc.textRenderer, Text.translatable("hud.sandevistan.online"), 0, 0, argb(alpha, t % 6 < 3 ? CYAN : YELLOW));
            m.pop();
        }
    }

    // ------------------------------------------------------------------ raios na tela

    private static void renderBolts(DrawContext ctx, int w, int h, long ms, int t) {
        long slice = ms / 70; // o desenho muda a cada ~70 ms: cintilar de raio

        // Raios de borda, sempre presentes.
        for (int i = 0; i < 4; i++) {
            Random r = new Random(slice * 7919L + i * 104729L);
            if (r.nextFloat() > 0.5f) continue;
            float sx, sy, ex, ey;
            if (r.nextBoolean()) {
                sx = r.nextFloat() * w;
                sy = r.nextBoolean() ? 0 : h;
                ex = sx + (r.nextFloat() - 0.5f) * w * 0.3f;
                ey = sy == 0 ? h * (0.15f + r.nextFloat() * 0.3f) : h * (0.55f + r.nextFloat() * 0.3f);
            } else {
                sy = r.nextFloat() * h;
                sx = r.nextBoolean() ? 0 : w;
                ey = sy + (r.nextFloat() - 0.5f) * h * 0.3f;
                ex = sx == 0 ? w * (0.1f + r.nextFloat() * 0.2f) : w * (0.7f + r.nextFloat() * 0.2f);
            }
            bolt(ctx, sx, sy, ex, ey, r, i % 2 == 0 ? CYAN : YELLOW, 150, 2);
        }

        // Tempestade ao ativar: raios caindo do topo da tela.
        if (t < 40) {
            int n = t < 20 ? 7 : 3;
            for (int i = 0; i < n; i++) {
                Random r = new Random(slice * 31L + i * 6151L + 5);
                float sx = r.nextFloat() * w;
                float ex = sx + (r.nextFloat() - 0.5f) * w * 0.25f;
                bolt(ctx, sx, 0, ex, h * (0.55f + r.nextFloat() * 0.45f), r, 0x9FF3FF, 230, 3);
            }
        }

        // Acerto / abate: raios convergindo para o centro.
        if (ClientState.flash > 0) {
            int n = ClientState.flashType == 2 ? 6 : 3;
            for (int i = 0; i < n; i++) {
                Random r = new Random(slice * 17L + i * 3571L + 11);
                float sx = r.nextBoolean() ? 0 : w;
                float sy = r.nextFloat() * h;
                float ex = w * (0.4f + r.nextFloat() * 0.2f);
                float ey = h * (0.4f + r.nextFloat() * 0.2f);
                bolt(ctx, sx, sy, ex, ey, r, ClientState.flashType == 2 ? YELLOW : CYAN, 220, 3);
            }
        }
    }

    /** Raio em zigue-zague com brilho e ramificações. */
    private static void bolt(DrawContext ctx, float x1, float y1, float x2, float y2, Random r, int rgb, int alpha, int thick) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 8) return;
        int segs = Math.max(6, (int) (len / 22));
        float nx = -dy / len, ny = dx / len;
        float amp = len * 0.07f;
        float px = x1, py = y1;
        for (int i = 1; i <= segs; i++) {
            float t = i / (float) segs;
            float off = i == segs ? 0 : (r.nextFloat() - 0.5f) * 2f * amp;
            float cx = x1 + dx * t + nx * off, cy = y1 + dy * t + ny * off;
            line(ctx, px, py, cx, cy, rgb, alpha, thick);
            if (thick >= 2 && i > 2 && i < segs - 1 && r.nextInt(7) == 0) {
                float ang = (float) (r.nextFloat() * Math.PI * 2);
                float bl = len * (0.12f + r.nextFloat() * 0.13f);
                line(ctx, cx, cy, cx + (float) Math.cos(ang) * bl, cy + (float) Math.sin(ang) * bl, rgb, alpha * 2 / 3, 1);
            }
            px = cx;
            py = cy;
        }
    }

    private static void line(DrawContext ctx, float x1, float y1, float x2, float y2, int rgb, int alpha, int thick) {
        float dx = x2 - x1, dy = y2 - y1;
        int steps = Math.max(1, (int) Math.max(Math.abs(dx), Math.abs(dy)));
        for (int s = 0; s <= steps; s += 2) {
            float t = s / (float) steps;
            int x = (int) (x1 + dx * t), y = (int) (y1 + dy * t);
            if ((s & 3) == 0) ctx.fill(x - thick - 2, y - thick - 2, x + thick + 3, y + thick + 3, argb(alpha / 6, rgb));
            ctx.fill(x - thick / 2, y - thick / 2, x + thick / 2 + 1, y + thick / 2 + 1, argb(alpha, 0xFFFFFF));
        }
    }

    private static void renderBar(DrawContext ctx, MinecraftClient mc, int w, int h) {
        Phase ph = ClientState.phase;
        float frac;
        int color;
        Text label;
        String secs = String.format("%.1f", ClientState.remaining / 20.0);

        switch (ph) {
            case ACTIVE -> {
                if (ClientState.total <= 0) { // duração infinita
                    frac = 1f;
                    color = 0xFF00E5FF;
                    label = Text.translatable("hud.sandevistan.infinite");
                } else {
                    frac = ClientState.remaining / (float) ClientState.total;
                    color = frac > 0.3f ? 0xFF00E5FF : 0xFFFFD500;
                    label = Text.translatable("hud.sandevistan.active", secs);
                }
            }
            case COOLDOWN -> {
                frac = ClientState.total > 0 ? 1f - ClientState.remaining / (float) ClientState.total : 1f;
                color = 0xFFFF6A00;
                label = Text.translatable("hud.sandevistan.cooldown", secs);
            }
            default -> {
                frac = 1f;
                color = 0xFF3DFF8A;
                label = Text.translatable("hud.sandevistan.ready", SandevistanClient.TOGGLE_KEY.getBoundKeyLocalizedText());
            }
        }

        int bw = 120, bh = 8;
        int x = w - bw - 12, y = h - 24;
        ctx.fill(x - 1, y - 1, x + bw + 1, y + bh + 1, 0xAA000000);
        ctx.fill(x, y, x + bw, y + bh, 0xFF181818);
        ctx.fill(x, y, x + (int) (bw * Math.max(0f, Math.min(1f, frac))), y + bh, color);
        ctx.drawTextWithShadow(mc.textRenderer, label, x, y - 11, color);
    }

    private static int argb(int a, int rgb) {
        return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0xFFFFFF);
    }

    private static int lerpColor(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
