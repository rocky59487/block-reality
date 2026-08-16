package com.blockreality.impl.client;

import com.blockreality.impl.BlockRealityMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side entry point, invoked through {@code DistExecutor} so a dedicated server
 * never loads this class or anything it references.
 *
 * <p>Class loading, not just execution, is what has to be kept off the server. A common
 * class that merely mentions a client type in a method signature can trip a
 * {@code NoClassDefFoundError} during verification, long before that method would run.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, value = Dist.CLIENT)
public final class ClientBootstrap {

    private ClientBootstrap() { }

    public static void init() {
        // The renderer, HUD and this tick handler register themselves through
        // @Mod.EventBusSubscriber, so there is nothing to wire here yet. The hook exists
        // because keybindings and model registration land here next.
    }

    /**
     * Which member the player is looking at, recomputed once per tick rather than once per
     * frame. It walks every station of every member, and at 60 fps that is work repeated
     * for an answer that cannot change faster than the player can turn.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ClientStressState.updateFocus();
    }
}
