package com.pvpduels.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Sent from the client when the "Totem to offhand" key is pressed. Carries no data. */
public record TotemPayload() implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("pvpduels", "totem");
	public static final CustomPacketPayload.Type<TotemPayload> TYPE = new CustomPacketPayload.Type<>(ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, TotemPayload> CODEC = StreamCodec.unit(new TotemPayload());

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
