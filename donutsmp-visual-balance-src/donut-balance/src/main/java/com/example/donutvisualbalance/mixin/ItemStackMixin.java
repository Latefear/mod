package com.example.donutvisualbalance.mixin;

import com.example.donutvisualbalance.DonutVisualBalanceClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-side only. Swaps the item name for amethyst/echo shards whenever
 * the game asks for it: the popup above the hotbar when you hold the item,
 * tooltips, and so on. Nothing changes on the server.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "getName", at = @At("RETURN"), cancellable = true)
    private void donutvisualbalance$fakeName(
        CallbackInfoReturnable<Text> cir
    ) {
        Text fake = DonutVisualBalanceClient.getFakeName(
            (ItemStack) (Object) this
        );
        if (fake != null) {
            cir.setReturnValue(fake);
        }
    }
}
