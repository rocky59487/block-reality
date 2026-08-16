package com.blockreality.impl;

import com.blockreality.impl.block.StructuralBlock;
import com.blockreality.impl.item.StressGlassesItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry contents for the demo slice.
 *
 * <p>Deliberately two blocks and one item. The demo is a vertical slice through every
 * layer, not a content pack: one material, one section, one failure mode. Everything else
 * arrives through the registries once the slice holds together.
 */
public final class BRContent {

    private BRContent() { }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, BlockRealityMod.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BlockRealityMod.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB,
                    BlockRealityMod.MOD_ID);

    /**
     * Structural steel. Its material and section tokens are what the engine is told;
     * the block itself carries no mechanics.
     *
     * <p>{@code steel_rect_200x400} is 200x400 — non-square, per the GATES.md fixture rule. A
     * square section hides local-axis mistakes because the two bending directions then
     * give the same answer, and that is precisely how both FrameCore issues in
     * ENGINE_FINDINGS.md survived seventy-odd fixtures.
     */
    public static final RegistryObject<Block> STEEL_BEAM = BLOCKS.register("steel_beam",
            () -> new StructuralBlock("steel", "steel_rect_200x400",
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(3.0f, 6.0f)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> STEEL_BEAM_ITEM = ITEMS.register("steel_beam",
            () -> new BlockItem(STEEL_BEAM.get(), new Item.Properties()));

    /**
     * A concrete floor slab. Its token is a <em>plate</em> token, and that is what makes it
     * a different element rather than a differently coloured beam.
     *
     * <p>The alternative — letting the engine infer "this looks like a floor" from the
     * shape — is what the extractor used to be forced into, and it gets it wrong in an
     * expensive way. A flat field of beam-token blocks extracts as a GRILLAGE: every block
     * belongs to one run along X and another along Z, so its weight is applied twice and
     * its stiffness counted twice, with nothing in the answer saying so. A token that names
     * the element makes the two cases distinguishable by construction.
     *
     * <p>{@code concrete_slab_200} is 200 mm thick inside a one-metre cube, which is D-004
     * again: the dimension is the engineering one, not the block's.
     */
    public static final RegistryObject<Block> CONCRETE_SLAB = BLOCKS.register("concrete_slab",
            () -> new StructuralBlock("concrete", "concrete_slab_200",
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(2.0f, 6.0f)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> CONCRETE_SLAB_ITEM = ITEMS.register("concrete_slab",
            () -> new BlockItem(CONCRETE_SLAB.get(), new Item.Properties()));

    /** The diagnostic instrument. It informs; it never blocks construction (DEMO_V0 §6). */
    public static final RegistryObject<Item> STRESS_GLASSES = ITEMS.register("stress_glasses",
            () -> new StressGlassesItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.blockreality"))
                    .icon(() -> STRESS_GLASSES.get().getDefaultInstance())
                    .displayItems((params, out) -> {
                        out.accept(STEEL_BEAM_ITEM.get());
                        out.accept(CONCRETE_SLAB_ITEM.get());
                        out.accept(STRESS_GLASSES.get());
                    })
                    .build());
}
