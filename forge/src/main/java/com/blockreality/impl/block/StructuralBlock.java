package com.blockreality.impl.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

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

    private final String materialToken;
    private final String sectionToken;

    public StructuralBlock(String materialToken, String sectionToken, BlockBehaviour.Properties props) {
        super(props);
        this.materialToken = materialToken;
        this.sectionToken = sectionToken;
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
