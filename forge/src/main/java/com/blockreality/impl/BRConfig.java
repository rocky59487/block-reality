package com.blockreality.impl;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Server-side configuration.
 *
 * <p>Small on purpose. Every entry here is either a path the mod cannot guess or a budget
 * a server operator has a legitimate reason to change. Nothing that affects the physics is
 * configurable: two servers running the same build must agree about whether a beam stands
 * up, or the numbers in a screenshot mean nothing.
 */
public final class BRConfig {

    public static final ForgeConfigSpec SPEC;
    public static final BRConfig INSTANCE;

    public final ForgeConfigSpec.ConfigValue<String> sidecarPath;
    public final ForgeConfigSpec.BooleanValue analysisEnabled;
    public final ForgeConfigSpec.IntValue minTicksBetweenSolves;
    public final ForgeConfigSpec.IntValue requestTimeoutMs;
    public final ForgeConfigSpec.IntValue bucklingBlockLimit;
    public final ForgeConfigSpec.DoubleValue demoLoadNewtons;

    private BRConfig(ForgeConfigSpec.Builder b) {
        b.comment("Block Reality — structural analysis").push("engine");

        sidecarPath = b
                .comment("Path to the br-sidecar executable.",
                        "Leave empty to search, in order: the br.sidecar system property,",
                        "the BR_SIDECAR environment variable, <game dir>/br-sidecar, then PATH.",
                        "If nothing is found the mod still loads and plays; analysis is off.")
                .define("sidecarPath", "");

        analysisEnabled = b
                .comment("Master switch. Off means no sidecar is ever started.")
                .define("analysisEnabled", true);

        requestTimeoutMs = b
                .comment("How long to wait for one analysis before treating the engine as wedged.")
                .defineInRange("requestTimeoutMs", 5000, 250, 120_000);

        minTicksBetweenSolves = b
                .comment("Minimum ticks between solves. A player laying a row of blocks",
                        "should produce one analysis, not twenty.")
                .defineInRange("minTicksBetweenSolves", 10, 1, 200);

        bucklingBlockLimit = b
                .comment("Above this many structural blocks the linear-buckling screen is",
                        "SKIPPED and the HUD says so; strength (D/C) always runs.",
                        "0 means never compute buckling.",
                        "",
                        "Measured cost of the screen itself, WSL/x86-64, shipped engine",
                        "(sidecar/repro_buckling_cost.py). Three shapes at a similar",
                        "block count, because the cost is not a function of blocks:",
                        "  straight beam,  300 blocks,   18 dof  ->    1 ms",
                        "  floor slab,     305 blocks, 1758 dof  ->   27 ms",
                        "  portal frame,   504 blocks, 1212 dof  ->   82 ms",
                        "and at the far end, where it does start to hurt:",
                        "  floor slab,    1616 blocks, 9624 dof  ->  208 ms",
                        "  portal frame,  1004 blocks, 2412 dof  -> 1005 ms",
                        "  portal frame,  2004 blocks, 4812 dof  -> 10.4 s",
                        "A frame costs ~24x a slab at the SAME dof: long chains of",
                        "identical bays give the eigensolver clustered modes to separate.",
                        "600 keeps a frame near 120 ms and a slab near 55 ms.")
                .defineInRange("bucklingBlockLimit", 600, 0, 1_000_000);

        b.pop().push("demo");

        demoLoadNewtons = b
                .comment("Test load applied by sneak-right-clicking a structural block, in newtons.",
                        "A demo affordance; it disappears once real loads exist.")
                .defineInRange("testLoadNewtons", 20_000.0, 1.0, 1.0e9);

        b.pop();
    }

    static {
        Pair<BRConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(BRConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }
}
