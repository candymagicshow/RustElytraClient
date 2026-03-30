package dev.rstminecraft.mixin;

import dev.rstminecraft.utils.BaritoneControlChecker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Pseudo
@Mixin(targets = {"baritone.behavior.LookBehavior", "baritone.f"}, remap = false)
public class BaritoneUpdateTarget {
    @Inject(method = "updateTarget", at = @At("HEAD"), require = 0)
    private void updateTarget(CallbackInfo ci) {
        BaritoneControlChecker.lookFlag = true;
    }
}
