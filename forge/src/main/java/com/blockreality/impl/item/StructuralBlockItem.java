package com.blockreality.impl.item;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/** Ordinary item use proposes construction. Only a separate server confirmation can place or debit. */
public final class StructuralBlockItem extends BlockItem {
    public StructuralBlockItem(Block block,Properties properties) {super(block,properties);}
    @Override public InteractionResult useOn(UseOnContext context) {
        if(context.getPlayer()==null)return InteractionResult.PASS;
        if(!context.getLevel().isClientSide)return InteractionResult.PASS;
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->com.blockreality.impl.client.ClientConstruction.open(context));
        return InteractionResult.CONSUME_PARTIAL;
    }
}
