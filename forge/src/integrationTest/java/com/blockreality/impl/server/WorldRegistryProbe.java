package com.blockreality.impl.server;

import com.blockreality.api.*;
import com.blockreality.impl.net.AnalysisUpdatePacket;
import com.blockreality.impl.net.StressResultPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.Commands;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/** Opt-in server acceptance probe; normal source sets and shipping jars exclude this class. */
@Mod.EventBusSubscriber(modid = "blockreality")
public final class WorldRegistryProbe {
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("br_registry_probe").requires(s -> s.hasPermission(4))
                .executes(context -> {
                    try {
                        var manager = StructureManager.of(context.getSource().getLevel());
                        var field = StructureManager.class.getDeclaredField("structural"); field.setAccessible(true);
                        WorldIndexData data = (WorldIndexData) field.get(manager);
                        var method = StructureManager.class.getDeclaredMethod("update", boolean.class); method.setAccessible(true);
                        var update = (AnalysisUpdatePacket) method.invoke(manager, true);
                        String message = "WR cells=" + data.size() + " index=" + hash(data.index().encode())
                                + " bootstrap=" + update.kind() + " payload=" + (update.result() != null)
                                + " detail=" + update.detail() + " native=" + fingerprint(manager.latest());
                        context.getSource().sendSuccess(() -> Component.literal(message), false);
                        return 1;
                    } catch (Exception e) { throw new IllegalStateException("WR probe failed", e); }
                }));
    }
    private static String fingerprint(AnalysisResult r) throws Exception {
        if (r == null) return "absent";
        // Normalize only the envelope revision. All delivered native samples, flags and f64 values stay exact.
        var fixed = new AnalysisResult(new WorldRevision(0), r.ok(), r.singular(), r.diagnostic(),
                r.maxDc(), r.governing(), r.governingKind(), r.islands(), r.singularIslands(),
                r.equilibriumResidual(), r.bucklingFactor(), r.bucklingState(), r.members(), r.shells(),
                r.unassigned(), r.overCapacity(), r.bucklingCritical(), r.bucklingIslands());
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            StressResultPacket.encode(StressResultPacket.of(fixed, "minecraft:overworld", false, Set.of(), Set.of(), 0), buffer);
            byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); return hash(bytes);
        } finally { buffer.release(); }
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
