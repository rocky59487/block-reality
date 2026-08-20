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
 * Registry contents.
 *
 * <p>The v0 demo was deliberately two blocks — a vertical slice, not a content pack. The
 * slice held, so the catalogue the engine always carried is now reachable from the
 * creative tab: three steel sections, sawn timber, a plain concrete beam, a brick pier,
 * and three plate tokens. Every token here has a closed-form gate behind it
 * (verify.py C1/C1b/C15 and the plate suites) BEFORE it got a block — "no capability
 * without a gate" runs in that order, not the other way around.
 *
 * <p>What is deliberately still absent: a brick <em>wall plate</em>. The plate screen is
 * an elastic von Mises surface check, and vM cannot see the tension/compression asymmetry
 * that governs a brittle material — a masonry wall it blessed would be wrong in the
 * unsafe direction. Brick therefore only exists as a pier, where the beam screen's five
 * separate ratios (tension among them) do the asymmetry honestly.
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

    // ---- the rest of the beam catalogue --------------------------------------
    // Same material, smaller sections: a 150x300 carries (150*300^2)/(200*400^2) = 42%
    // of the 200x400's section modulus, so the same span fails visibly sooner — which
    // is the point of having more than one size on the shelf.
    public static final RegistryObject<Block> STEEL_BEAM_150 = BLOCKS.register("steel_beam_150x300",
            () -> new StructuralBlock("steel", "steel_rect_150x300",
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.DEEPSLATE)
                            .strength(3.0f, 6.0f)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> STEEL_BEAM_150_ITEM = ITEMS.register("steel_beam_150x300",
            () -> new BlockItem(STEEL_BEAM_150.get(), new Item.Properties()));

    public static final RegistryObject<Block> STEEL_BEAM_100 = BLOCKS.register("steel_beam_100x200",
            () -> new StructuralBlock("steel", "steel_rect_100x200",
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.DEEPSLATE)
                            .strength(3.0f, 6.0f)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> STEEL_BEAM_100_ITEM = ITEMS.register("steel_beam_100x200",
            () -> new BlockItem(STEEL_BEAM_100.get(), new Item.Properties()));

    /**
     * Plain (unreinforced) concrete beam. The token says {@code concrete_rect_400x600}
     * because that is what the engine solves: a solid rectangle of concrete, tension
     * allowable 3 MPa — so it cracks where a textbook says unreinforced concrete
     * cracks. An honest RC composite section is issue #13, not a rename.
     */
    public static final RegistryObject<Block> CONCRETE_BEAM = BLOCKS.register("concrete_beam",
            () -> new StructuralBlock("concrete", "concrete_rect_400x600",
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_GRAY)
                            .strength(2.0f, 6.0f)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> CONCRETE_BEAM_ITEM = ITEMS.register("concrete_beam",
            () -> new BlockItem(CONCRETE_BEAM.get(), new Item.Properties()));

    /** Sawn timber, 140x240. Hand-breakable like wood; the section is gated by C15. */
    public static final RegistryObject<Block> TIMBER_BEAM = BLOCKS.register("timber_beam",
            () -> new StructuralBlock("timber", "timber_rect_140x240",
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(1.5f, 3.0f)
                            .sound(SoundType.WOOD)));

    public static final RegistryObject<Item> TIMBER_BEAM_ITEM = ITEMS.register("timber_beam",
            () -> new BlockItem(TIMBER_BEAM.get(), new Item.Properties()));

    /** Brick pier, 230x350. Beam-screened on purpose — see the class comment. */
    public static final RegistryObject<Block> BRICK_PIER = BLOCKS.register("brick_pier",
            () -> new StructuralBlock("brick", "brick_rect_230x350",
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_RED)
                            .strength(2.0f, 6.0f)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> BRICK_PIER_ITEM = ITEMS.register("brick_pier",
            () -> new BlockItem(BRICK_PIER.get(), new Item.Properties()));

    // ---- the rest of the plate catalogue -------------------------------------
    public static final RegistryObject<Block> CONCRETE_SLAB_150 = BLOCKS.register("concrete_slab_150",
            () -> new StructuralBlock("concrete", "concrete_slab_150",
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_LIGHT_GRAY)
                            .strength(2.0f, 6.0f)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> CONCRETE_SLAB_150_ITEM = ITEMS.register("concrete_slab_150",
            () -> new BlockItem(CONCRETE_SLAB_150.get(), new Item.Properties()));

    /** 20 mm steel plate — a thin shell, so out-of-plane it buckles long before it yields. */
    public static final RegistryObject<Block> STEEL_PLATE = BLOCKS.register("steel_plate",
            () -> new StructuralBlock("steel", "steel_plate_20",
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_BLACK)
                            .strength(3.0f, 6.0f)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> STEEL_PLATE_ITEM = ITEMS.register("steel_plate",
            () -> new BlockItem(STEEL_PLATE.get(), new Item.Properties()));

    /** The diagnostic instrument. It informs; it never blocks construction (DEMO_V0 §6). */
    public static final RegistryObject<Item> STRESS_GLASSES = ITEMS.register("stress_glasses",
            () -> new StressGlassesItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.blockreality"))
                    .icon(() -> STRESS_GLASSES.get().getDefaultInstance())
                    .displayItems((params, out) -> {
                        // Beams by material, then plates, then the instrument.
                        out.accept(STEEL_BEAM_ITEM.get());
                        out.accept(STEEL_BEAM_150_ITEM.get());
                        out.accept(STEEL_BEAM_100_ITEM.get());
                        out.accept(CONCRETE_BEAM_ITEM.get());
                        out.accept(TIMBER_BEAM_ITEM.get());
                        out.accept(BRICK_PIER_ITEM.get());
                        out.accept(CONCRETE_SLAB_ITEM.get());
                        out.accept(CONCRETE_SLAB_150_ITEM.get());
                        out.accept(STEEL_PLATE_ITEM.get());
                        out.accept(STRESS_GLASSES.get());
                    })
                    .build());
}
