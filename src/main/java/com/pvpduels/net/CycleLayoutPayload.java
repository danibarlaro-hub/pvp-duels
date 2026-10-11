package com.pvpduels.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Sent from the client to the server when the "Next layout" or "Previous layout" key is pressed. */
public record CycleLayoutPayload(boolean forward) implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("pvpduels", "cycle_layout");
	public static final CustomPacketPayload.Type<CycleLayoutPayload> TYPE = new CustomPacketPayload.Type<>(ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, CycleLayoutPayload> CODEC =
		StreamCodec.composite(ByteBufCodecs.BOOL, CycleLayoutPayload::forward, CycleLayoutPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
