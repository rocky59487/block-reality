package com.blockreality.impl.server;

import com.blockreality.impl.BRContent;
import com.blockreality.impl.block.StructuralBlock;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Opt-in, fixed isolated-world fixture operations. Never present in a normal jar. */
@Mod.EventBusSubscriber(modid="blockreality")
public final class ConstructionProbe {
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("br_identity_snapshot").requires(s->s.hasPermission(4)).executes(c->{
            requireWorld(c.getSource().getLevel());
            var m=StructureManager.of(c.getSource().getLevel());var l=m.constructionObjects();
            try {
                String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(l.encode()));
                c.getSource().sendSuccess(()->Component.literal("CI "+m.objectStatus()+" namespace="+l.graph().namespace()
                        +" active="+l.graph().activeCount()+" records="+l.graph().records().size()+" cells="+l.cellCount()
                        +" next="+l.graph().nextId()+" sha="+sha),false);return 1;
            }catch(Exception e){throw new IllegalStateException(e);}
        }));
        event.getDispatcher().register(Commands.literal("br_identity_replace").requires(s->s.hasPermission(4)).executes(c->{
            var level=c.getSource().getLevel();requireWorld(level);
            for(int x=4104;x<=4108;x++)level.setBlock(new BlockPos(x,200,8),Blocks.AIR.defaultBlockState(),3);
            for(int x=4104;x<=4108;x++)level.setBlock(new BlockPos(x,200,8),BRContent.STEEL_BEAM.get().defaultBlockState()
                    .setValue(StructuralBlock.AXIS,StructuralBlock.Axis.X),3);
            c.getSource().sendSuccess(()->Component.literal("CI replaced all five original cells in one server command"),false);return 1;
        }));
    }
    private static void requireWorld(net.minecraft.server.level.ServerLevel level){
        if(!level.getServer().getWorldData().getLevelName().equals("construction-smoke"))throw new IllegalStateException("isolated world required");
    }
}
