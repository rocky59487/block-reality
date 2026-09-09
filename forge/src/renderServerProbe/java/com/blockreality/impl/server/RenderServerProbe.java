package com.blockreality.impl.server;

import com.google.gson.JsonObject;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Read-only state in the isolated rendering server, never an ordinary jar entry. */
@Mod.EventBusSubscriber(modid = "blockreality")
public final class RenderServerProbe {
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("br_render_state").requires(s -> s.hasPermission(4)).executes(c -> {
            var level = c.getSource().getLevel();
            if (!level.getServer().getWorldData().getLevelName().equals("render-probe")
                    || level.getServer().getPort() != 25594) throw new IllegalStateException("isolated rendering world required");
            var manager = StructureManager.of(level); var result = manager.latest();
            var doc = new JsonObject();
            doc.addProperty("worldRevision", manager.gate().current().value());
            doc.addProperty("resultRevision", result == null ? -1 : result.revision().value());
            doc.addProperty("kind", manager.noticeKind().name());
            doc.addProperty("detail", manager.noticeDetail());
            doc.addProperty("objectsReady", manager.objectsReady());
            doc.addProperty("cells", manager.structuralBlockCount());
            doc.addProperty("members", result == null ? 0 : result.members().size());
            doc.addProperty("shells", result == null ? 0 : result.shells().size());
            doc.addProperty("maxDc", result == null ? 0 : result.maxDc());
            doc.addProperty("overCapacity", result != null && result.overCapacity());
            doc.addProperty("bucklingCritical", result != null && result.bucklingCritical());
            c.getSource().sendSuccess(() -> Component.literal(doc.toString()), false); return 1;
        }));
    }
}
