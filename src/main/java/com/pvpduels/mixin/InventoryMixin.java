package com.pvpduels.mixin;

import com.pvpduels.duel.LobbyKit;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pressing Q on a lobby hotbar item drops nothing. */
@Mixin(Inventory.class)
public abstract class InventoryMixin {
	@Inject(method = "removeFromSelected", at = @At("HEAD"), cancellable = true)
	private void pvpduels$lobbyItemsStay(boolean all, CallbackInfoReturnable<ItemStack> cir) {
		Inventory self = (Inventory) (Object) this;
		if (self.player instanceof ServerPlayer sp && LobbyKit.locked(sp, self.getSelectedSlot())) {
			cir.setReturnValue(ItemStack.EMPTY);
		}
	}
}
