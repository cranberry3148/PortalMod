package net.portalmod.mixins.client;

import net.minecraft.client.settings.GraphicsFanciness;
import net.portalmod.PortalMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GraphicsFanciness.class)
public class GraphicsFancinessMixin {
    @Inject(method = "byId", at = @At("RETURN"), cancellable = true)
    private static void pmSkipFabulousById(int id, CallbackInfoReturnable<GraphicsFanciness> cir) {
        if(cir.getReturnValue() == GraphicsFanciness.FABULOUS) {
            PortalMod.LOGGER.info("Fabulous graphics mode not supported, forcing Fancy");
            cir.setReturnValue(GraphicsFanciness.FANCY);
        }
    }

    @Inject(method = "cycleNext", at = @At("HEAD"), cancellable = true)
    private void pmSkipFabulousCycle(CallbackInfoReturnable<GraphicsFanciness> cir) {
        GraphicsFanciness self = (GraphicsFanciness)(Object)this;
        if(self == GraphicsFanciness.FANCY || self == GraphicsFanciness.FABULOUS) {
            PortalMod.LOGGER.info("Fabulous graphics mode not supported, forcing {}", self);
            cir.setReturnValue(GraphicsFanciness.FAST);
        }
    }
}
