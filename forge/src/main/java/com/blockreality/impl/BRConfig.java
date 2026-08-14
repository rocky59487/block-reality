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
