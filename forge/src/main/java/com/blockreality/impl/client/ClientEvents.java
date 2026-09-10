package com.blockreality.impl.client;

import com.blockreality.impl.BlockRealityMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Clears the client stress state when its world stops being the world on screen.
 *
 * <p>Without this, the overlay survived logout, server switches, respawn and dimension
 * travel: the old world's stress contours were painted onto whatever blocks now stand
 * at the same coordinates in the new one — the Nether wearing the Overworld's D/C
 * colours (#41). The server sends a bootstrap snapshot after each transition.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() { }

    /** Leaving a server (or single-player world). */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut e) {
        ClientStressState.clear();
        ClientConstruction.leave();
    }

    /**
     * Respawn and dimension change both arrive here: the client player entity is
     * cloned into the destination level. Same-dimension respawn clears too — the
     * server sends its current snapshot without waiting for another solve.
     */
    @SubscribeEvent
    public static void onClone(ClientPlayerNetworkEvent.Clone e) {
        ClientStressState.leaveDimension();
        ClientConstruction.leave();
    }
}
