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
 * <p>Note what does <em>not</em> happen here: the sidecar is not started. It is lazy, and
 * a player who never places a structural block never pays for a process. More to the
 * point, a mod that failed to load because an optional native binary was missing would be
 * a worse mod than one that simply cannot analyse anything.
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

    /**
     * Shut the sidecars down with the server. The child processes would exit on their own
     * when stdin closed, but doing it explicitly means the shutdown is ordered rather than
     * racing the JVM's exit.
     */
    private static void onServerStopping(ServerStoppingEvent event) {
        StructureManager.shutdownAll();
    }
}
