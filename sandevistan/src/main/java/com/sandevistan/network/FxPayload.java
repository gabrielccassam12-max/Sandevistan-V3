package com.sandevistan.network;

import com.sandevistan.Sandevistan;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** Servidor -> um jogador: dispara efeito de tela. type: 0 = acertou, 1 = desviou, 2 = matou. */
public record FxPayload(int type) implements CustomPayload {
    public static final CustomPayload.Id<FxPayload> ID = new CustomPayload.Id<>(Sandevistan.id("fx"));
    public static final PacketCodec<RegistryByteBuf, FxPayload> CODEC = PacketCodec.of(FxPayload::write, FxPayload::read);

    private void write(RegistryByteBuf buf) {
        buf.writeVarInt(type);
    }

    private static FxPayload read(RegistryByteBuf buf) {
        return new FxPayload(buf.readVarInt());
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
