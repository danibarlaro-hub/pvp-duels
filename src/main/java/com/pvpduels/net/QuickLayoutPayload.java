package com.pvpduels.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Sent from the client when one of the three "Quick layout" keys is pressed. index is 0, 1 or 2. */
public record QuickLayoutPayload(int index) implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("pvpduels", "quick_layout");
	public static final CustomPacketPayload.Type<QuickLayoutPayload> TYPE = new CustomPacketPayload.Type<>(ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, QuickLayoutPayload> CODEC =
		StreamCodec.composite(ByteBufCodecs.VAR_INT, QuickLayoutPayload::index, QuickLayoutPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
