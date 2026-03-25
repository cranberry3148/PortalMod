package net.portalmod.core.event;

import net.minecraftforge.event.entity.player.SleepingTimeCheckEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.portalmod.PortalMod;

@Mod.EventBusSubscriber(modid = PortalMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SleepEventHandler {
    @SubscribeEvent
    public static void onSleepTimeCheck(SleepingTimeCheckEvent event) {
        // This forces the check to return true regardless of time
        event.setResult(Event.Result.ALLOW);
    }
}
