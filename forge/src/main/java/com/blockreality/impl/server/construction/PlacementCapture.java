package com.blockreality.impl.server.construction;

import com.blockreality.core.transaction.ConstructionTransaction.Value;
import com.blockreality.impl.block.StructuralBlock;
import com.blockreality.impl.net.ConstructionProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.io.IOException;
import java.util.UUID;
import static com.blockreality.impl.net.ConstructionProtocol.Status.*;

/** Read-only ordinary-product proposal. Protection events are dispatched separately by the commit owner. */
record PlacementCapture(ConstructionProtocol.Preview preview, PlacementPlan plan,
                        Value cellBefore, Value cellAfter, Value itemBefore, Value itemAfter) {
    static final class Refused extends Exception {
        final ConstructionProtocol.Status status;
        Refused(ConstructionProtocol.Status status) { super(status.name());this.status=status; }
    }
    static PlacementCapture capture(ServerPlayer actor,ConstructionProtocol.Preview preview,UUID token) throws Refused,IOException {
        ServerLevel level=actor.serverLevel();
        if(!actor.isAlive() || actor.isSpectator() || !actor.mayBuild() || actor.containerMenu!=actor.inventoryMenu
                || !actor.containerMenu.getCarried().isEmpty() || actor.isCreative()!=actor.getAbilities().instabuild)
            throw new Refused(PERMISSION);
        if(!level.dimension().location().toString().equals(preview.dimension()))throw new Refused(CONFLICT);
        if(level.captureBlockSnapshots || level.restoringBlockSnapshots)throw new Refused(BUSY);
        ItemStack held=actor.getItemInHand(preview.hand());
        if(held.isEmpty() || !(held.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof StructuralBlock product)
                || !ownProduct(product) || !BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(preview.product())
                || actor.getCooldowns().isOnCooldown(held.getItem()) || !product.isEnabled(level.enabledFeatures()))
            throw new Refused(CONFLICT);
        requireCell(actor,level,preview.clicked());
        BlockHitResult hit=new BlockHitResult(preview.hit(),preview.face(),preview.clicked(),false);
        BlockPlaceContext context=new BlockPlaceContext(actor,preview.hand(),held,hit);
        BlockPos target=context.getClickedPos();requireCell(actor,level,target);
        BlockPos against=target.relative(preview.face().getOpposite());
        if(level.getChunkSource().getChunkNow(against.getX()>>4,against.getZ()>>4)==null)throw new Refused(CONFLICT);
        BlockState before=level.getChunkSource().getChunkNow(target.getX()>>4,target.getZ()>>4).getBlockState(target);
        if(!context.canPlace() || before.hasBlockEntity()
                || level.getChunkSource().getChunkNow(target.getX()>>4,target.getZ()>>4).getBlockEntitiesPos().contains(target))
            throw new Refused(CONFLICT);
        BlockState after=product.defaultBlockState().setValue(StructuralBlock.AXIS,StructuralBlock.Axis.values()[preview.axis()+1]);
        if(before==after || !after.canSurvive(level,target) || !level.isUnobstructed(after,target,CollisionContext.of(actor)))
            throw new Refused(CONFLICT);
        int slot=preview.hand()==InteractionHand.MAIN_HAND?actor.getInventory().selected:40;
        Value itemBefore=LivePlayerInventory.image(held);LivePlayerInventory.decode(itemBefore);
        Value itemAfter=actor.isCreative()?itemBefore:LivePlayerInventory.debitOne(itemBefore);
        Value cellBefore=CellStateImage.encode(before),cellAfter=CellStateImage.encode(after);
        PlacementPlan plan=new PlacementPlan(token,actor.getUUID(),preview.dimension(),preview.clicked(),target,preview.face(),preview.hit(),
                preview.hand(),slot,preview.product(),preview.axis(),actor.isCreative(),PlacementPlan.digest(cellBefore),PlacementPlan.digest(cellAfter),
                PlacementPlan.digest(itemBefore),PlacementPlan.digest(itemAfter));
        return new PlacementCapture(preview,plan,cellBefore,cellAfter,itemBefore,itemAfter);
    }
    boolean stillMatches(ServerPlayer actor) throws Refused,IOException {
        PlacementCapture current=capture(actor,preview,plan.offer());
        return plan.equals(current.plan()) && cellBefore.equals(current.cellBefore()) && cellAfter.equals(current.cellAfter())
                && itemBefore.equals(current.itemBefore()) && itemAfter.equals(current.itemAfter());
    }
    static boolean ownProduct(StructuralBlock product) {
        return com.blockreality.impl.BRContent.BLOCKS.getEntries().stream().anyMatch(entry -> entry.get()==product);
    }
    int retainedBytes() throws IOException {
        return plan.image().bytes().length+cellBefore.bytes().length+cellAfter.bytes().length
                +itemBefore.bytes().length+(itemAfter.present()?itemAfter.bytes().length:0);
    }
    private static void requireCell(ServerPlayer actor,ServerLevel level,BlockPos pos) throws Refused {
        if(!actor.canReachRaw(pos,1.5))throw new Refused(OUT_OF_REACH);
        if(level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.mayInteract(actor,pos))throw new Refused(PERMISSION);
        if(level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null)throw new Refused(CONFLICT);
    }
}
