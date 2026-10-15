package com.pvpduels.mixin;

import com.pvpduels.duel.DuelManager;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts the swings of a duelist (the game sends one packet for every swing of the arm), to know how many of them hit. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SwingMixin {
	@Inject(method = "handleAnimate", at = @At("HEAD"))
	private void pvpduels$countSwing(ServerboundSwingPacket packet, CallbackInfo ci) {
		ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
		if (player != null) DuelManager.onSwing(player);
	}
}
