package com.pvpduels.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Sent from the client when one of the three "Quick kit" keys (A, B, C) is pressed. index is 0, 1 or 2. */
public record QuickKitPayload(int index) implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("pvpduels", "quick_kit");
	public static final CustomPacketPayload.Type<QuickKitPayload> TYPE = new CustomPacketPayload.Type<>(ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, QuickKitPayload> CODEC =
		StreamCodec.composite(ByteBufCodecs.VAR_INT, QuickKitPayload::index, QuickKitPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
