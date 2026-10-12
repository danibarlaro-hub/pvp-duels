package com.pvpduels.mixin;

import com.pvpduels.duel.LobbyKit;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes the lobby hotbar items immovable: a click that would move, swap or throw one of them does nothing. */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
	@Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
	private void pvpduels$lobbyItemsStay(int slotId, int button, ContainerInput input, Player player, CallbackInfo ci) {
		if (player instanceof ServerPlayer sp && LobbyKit.blocksClick((AbstractContainerMenu) (Object) this, slotId, button, input, sp)) {
			ci.cancel();
			((AbstractContainerMenu) (Object) this).sendAllDataToRemote();
		}
	}
}
