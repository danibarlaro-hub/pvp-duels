package com.pvpduels.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Sent from the client when a key of the films is pressed: action 0 skips the film, action 1 switches the free camera. */
public record FilmKeyPayload(int action) implements CustomPacketPayload {
	public static final int SKIP = 0;
	public static final int FREE_CAM = 1;
	public static final Identifier ID = Identifier.fromNamespaceAndPath("pvpduels", "film_key");
	public static final CustomPacketPayload.Type<FilmKeyPayload> TYPE = new CustomPacketPayload.Type<>(ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, FilmKeyPayload> CODEC =
		StreamCodec.composite(ByteBufCodecs.VAR_INT, FilmKeyPayload::action, FilmKeyPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
