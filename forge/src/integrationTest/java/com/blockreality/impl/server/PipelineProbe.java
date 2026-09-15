package com.blockreality.impl.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Guarded measurement output for the isolated server; never in the ordinary jar. */
@Mod.EventBusSubscriber(modid="blockreality")
public final class PipelineProbe {
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        for (String command : new String[]{"br_pipeline_state","br_pipeline_probe"}) {
            event.getDispatcher().register(Commands.literal(command).requires(s->s.hasPermission(4)).executes(c->{
                var level=c.getSource().getLevel();
                if(!level.getServer().getWorldData().getLevelName().equals("pipeline-smoke"))throw new IllegalStateException("isolated world required");
                var m=StructureManager.of(level);var result=m.latest();var doc=new JsonObject();
                doc.addProperty("revision",m.gate().current().value());
                doc.addProperty("resultRevision",result==null?-1:result.revision().value());
                doc.addProperty("current",result!=null&&result.ok()&&result.revision().equals(m.gate().current())
                        &&m.noticeKind()==com.blockreality.impl.net.AnalysisUpdatePacket.Kind.RESULT);
                doc.addProperty("cells",m.structuralBlockCount());doc.addProperty("objectsReady",m.objectsReady());
                doc.addProperty("members",result==null?0:result.members().size());doc.addProperty("shells",result==null?0:result.shells().size());
                doc.addProperty("notice",m.noticeKind().name());doc.addProperty("detail",m.noticeDetail());
                if(command.equals("br_pipeline_probe")) {
                    doc.add("profile",new Gson().toJsonTree(m.profile().snapshot()));
                    doc.addProperty("heapUsed",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                    doc.addProperty("heapMax",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
                    doc.addProperty("gcCount",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionCount())).sum());
                    doc.addProperty("gcMs",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum());
                    try {
                        doc.addProperty("graphSha",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(m.constructionObjects().encode())));
                        var field=StructureManager.class.getDeclaredField("latestPacket");field.setAccessible(true);
                        var packet=(com.blockreality.impl.net.StressResultPacket)field.get(m);
                        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                        try {
                            if(packet!=null)com.blockreality.impl.net.StressResultPacket.encode(packet,buffer);
                            doc.addProperty("packetBytes",buffer.readableBytes());
                        }finally{buffer.release();}
                        doc.addProperty("packetTruncated",packet!=null&&(packet.membersTruncated()||packet.shellsTruncated()));
                    } catch(Exception e){throw new IllegalStateException(e);}
                }
                c.getSource().sendSuccess(()->Component.literal(doc.toString()),false);return 1;
            }));
        }
    }
}
