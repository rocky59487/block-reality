package com.blockreality.impl.item;

import com.blockreality.impl.block.StructuralBlock;
import com.blockreality.impl.server.StructureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/**
 * The stress glasses.
 *
 * <ul>
 *   <li><strong>Use in the air</strong> — cycle the lens. Client-side only; which lens a
 *       player is looking through is not the server's business.
 *   <li><strong>Sneak-use on a structural block</strong> — toggle a 20 kN test load. This
 *       is the demo's way of applying load without a crane, a mass or a construction
 *       chain, and it goes away as soon as any of those exist.
 * </ul>
 *
 * <p>What it never does is refuse anything. It is a diagnostic instrument, not a permit
 * office: a player is free to build something that will fall down and watch it happen
 * (DEMO_V0 §6).
 */
public class StressGlassesItem extends Item {

    public StressGlassesItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> com.blockreality.impl.client.ClientStressState::cycleMode);
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;

        if (!(level.getBlockState(ctx.getClickedPos()).getBlock() instanceof StructuralBlock)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel server)) return InteractionResult.PASS;

        boolean added = StructureManager.of(server).toggleLoad(ctx.getClickedPos());
        player.displayClientMessage(
                Component.translatable(added ? "br.load.added" : "br.load.removed"), true);
        return InteractionResult.SUCCESS;
    }
}
