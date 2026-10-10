package com.pvpduels.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Sent from the client to the server when the "Sort hotbar" key is pressed. Carries no data. */
public record SortRequestPayload() implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("pvpduels", "sort_request");
	public static final CustomPacketPayload.Type<SortRequestPayload> TYPE = new CustomPacketPayload.Type<>(ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, SortRequestPayload> CODEC = StreamCodec.unit(new SortRequestPayload());

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
