package com.blockreality.impl.client;

import com.blockreality.impl.BlockRealityMod;
import com.blockreality.impl.block.StructuralBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Preview geometry is the declared product box, with ordinary depth occlusion and no world mutation. */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid=BlockRealityMod.MOD_ID,value=Dist.CLIENT)
public final class PlacementGhost {
    private PlacementGhost() { }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)return;
        var session=ClientConstruction.ghost();if(session==null)return;
        var offer=session.offer;
        if(!(BuiltInRegistries.ITEM.get(new ResourceLocation(offer.product())) instanceof BlockItem item)
                || !(item.getBlock() instanceof StructuralBlock block))return;
        var box=block.form(block.defaultBlockState().setValue(StructuralBlock.AXIS,StructuralBlock.Axis.values()[offer.axis()+1])).box();
        var position=offer.target();var camera=event.getCamera().getPosition();var pose=event.getPoseStack();
        var buffers=Minecraft.getInstance().renderBuffers().bufferSource();pose.pushPose();
        try {
            pose.translate(position.getX()-camera.x,position.getY()-camera.y,position.getZ()-camera.z);
            LevelRenderer.renderLineBox(pose,buffers.getBuffer(RenderType.lines()),box.minX(),box.minY(),box.minZ(),
                    box.maxX(),box.maxY(),box.maxZ(),0.624f,0.91f,1f,0.9f);
            buffers.endBatch(RenderType.lines());
        } finally {pose.popPose();}
    }
}
