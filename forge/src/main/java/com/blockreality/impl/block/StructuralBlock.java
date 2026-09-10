package com.blockreality.impl.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.util.StringRepresentable;

/**
 * A block that participates in the structural model.
 *
 * <p>It holds a material token and a section token and nothing else. No stiffness, no
 * strength, no stress, no failure state — D-006 puts all of that on the engine's side of
 * the boundary, and a block that cached any of it would immediately become a second,
 * disagreeing source of truth.
 *
 * <p>The section token is also what makes D-004 real: section is decoupled from block
 * size. A one-metre cube declares a 200x400 section, and the engine believes the token,
 * not the cube.
 */
public class StructuralBlock extends Block {

    /** Missing legacy properties remain explicitly undeclared instead of inventing a physical axis. */
    public enum Axis implements StringRepresentable {
        UNDECLARED(-1), X(0), Y(1), Z(2);
        private final int wire;
        Axis(int wire) { this.wire = wire; }
        public int wire() {
            if (this == UNDECLARED) throw new IllegalStateException("placement axis is undeclared");
            return wire;
        }
        public Axis next() { return switch (this) { case UNDECLARED, Z -> X; case X -> Y; case Y -> Z; }; }
        public Axis quarterTurn() { return switch (this) { case X -> Z; case Z -> X; default -> this; }; }
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
    public static final EnumProperty<Axis> AXIS = EnumProperty.create("axis", Axis.class);

    private final String materialToken;
    private final String sectionToken;

    public StructuralBlock(String materialToken, String sectionToken, BlockBehaviour.Properties props) {
        super(props);
        this.materialToken = materialToken;
        this.sectionToken = sectionToken;
        registerDefaultState(stateDefinition.any().setValue(AXIS, Axis.UNDECLARED));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        Axis axis = switch (context.getClickedFace().getAxis()) { case X -> Axis.X; case Y -> Axis.Y; case Z -> Axis.Z; };
        return defaultBlockState().setValue(AXIS, axis);
    }

    @Override public BlockState rotate(BlockState state, Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90
                ? state.setValue(AXIS, state.getValue(AXIS).quarterTurn()) : state;
    }

    @Override public void onPlace(BlockState state, net.minecraft.world.level.Level level,
            net.minecraft.core.BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (level instanceof net.minecraft.server.level.ServerLevel server
                && !level.captureBlockSnapshots && !level.restoringBlockSnapshots)
            com.blockreality.impl.server.StructureManager.observedStructure(server, pos, state);
    }

    @Override public void onRemove(BlockState state, net.minecraft.world.level.Level level,
            net.minecraft.core.BlockPos pos, BlockState next, boolean moving) {
        if (state.getBlock() != next.getBlock() && level instanceof net.minecraft.server.level.ServerLevel server
                && !level.captureBlockSnapshots && !level.restoringBlockSnapshots)
            com.blockreality.impl.server.StructureManager.removedStructure(server, pos);
        super.onRemove(state, level, pos, next, moving);
    }

    @Override public net.minecraft.world.InteractionResult use(BlockState state, net.minecraft.world.level.Level level,
            net.minecraft.core.BlockPos pos, net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        if (!player.isShiftKeyDown() || !player.getItemInHand(hand).isEmpty() || !player.mayBuild())
            return net.minecraft.world.InteractionResult.PASS;
        if (!level.isClientSide && level instanceof net.minecraft.server.level.ServerLevel server) {
            Axis axis = state.getValue(AXIS).next();
            if (level.setBlock(pos, state.setValue(AXIS, axis), 3)) {
                com.blockreality.impl.server.StructureManager.observedStructure(server, pos, state.setValue(AXIS, axis));
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("br.placement.axis", axis.name()), true);
            }
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override public void appendHoverText(net.minecraft.world.item.ItemStack stack,
            @javax.annotation.Nullable net.minecraft.world.level.BlockGetter level,
            java.util.List<net.minecraft.network.chat.Component> text, net.minecraft.world.item.TooltipFlag flag) {
        super.appendHoverText(stack, level, text, flag);
        ProductTooltip.append(materialToken, sectionToken, text);
        text.add(net.minecraft.network.chat.Component.translatable("br.placement.hint"));
    }

    public String materialToken() { return materialToken; }

    public String sectionToken() { return sectionToken; }

    /**
     * A piston cannot move a structural block.
     *
     * <p>Not a gameplay preference — a modelling one. Every other way a block moves fires
     * an event this mod listens to; a piston push moves it with none, so the analysis
     * would keep solving a member at the position it used to occupy and report stresses
     * for a structure that is not there (PR26_REVIEW DF-01). Refusing the push is honest
     * and reversible; silently modelling the wrong building is neither.
     */
    @Override
    public net.minecraft.world.level.material.PushReaction getPistonPushReaction(
            net.minecraft.world.level.block.state.BlockState state) {
        return net.minecraft.world.level.material.PushReaction.BLOCK;
    }
}
