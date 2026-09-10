package com.blockreality.impl;

import com.blockreality.impl.net.BRNetwork;
import com.blockreality.impl.server.StructureManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entry point.
 *
 * <p>The native engine is loaded lazily by an analysis worker. A missing library disables
 * analysis without preventing the rest of the mod from loading.
 */
@Mod(BlockRealityMod.MOD_ID)
public final class BlockRealityMod {

    public static final String MOD_ID = "blockreality";
    public static final Logger LOG = LoggerFactory.getLogger("BlockReality");

    public BlockRealityMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();

        // SERVER type, not COMMON: the analysis runs on the logical server, and a client
        // joining someone else's world has no business overriding their engine path.
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.SERVER, BRConfig.SPEC);

        BRContent.BLOCKS.register(bus);
        BRContent.ITEMS.register(bus);
        BRContent.TABS.register(bus);

        bus.addListener(BRNetwork::onCommonSetup);

        MinecraftForge.EVENT_BUS.register(StructureManager.class);
        MinecraftForge.EVENT_BUS.register(com.blockreality.impl.command.BRCommand.class);
        MinecraftForge.EVENT_BUS.addListener(BlockRealityMod::onServerStopping);

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> com.blockreality.impl.client.ClientBootstrap::init);
    }

    /** Revoke pending results before scheduling native cleanup and stopping analysis workers. */
    private static void onServerStopping(ServerStoppingEvent event) {
        StructureManager.shutdownAll();
    }
}
