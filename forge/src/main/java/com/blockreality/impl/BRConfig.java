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

    public enum EngineMode { INPROCESS, OFF }
    public final ForgeConfigSpec.ConfigValue<String> enginePath;
    public final ForgeConfigSpec.EnumValue<EngineMode> mode;
    public final ForgeConfigSpec.IntValue numThreads;
    public final ForgeConfigSpec.BooleanValue bucklingEnabled;
    public final ForgeConfigSpec.IntValue bucklingDofBudget;
    public final ForgeConfigSpec.BooleanValue analysisEnabled;
    public final ForgeConfigSpec.IntValue minTicksBetweenSolves;
    public final ForgeConfigSpec.IntValue requestTimeoutMs;
    public final ForgeConfigSpec.DoubleValue demoLoadNewtons;

    private BRConfig(ForgeConfigSpec.Builder b) {
        b.comment("Block Reality — structural analysis").push("engine");

        mode = b.comment("INPROCESS loads the native engine; OFF performs no discovery or unpacking.")
                .defineEnum("mode", EngineMode.INPROCESS);
        enginePath = b.comment("Optional native shared library path. An invalid explicit path is refused.",
                        "Otherwise: br.engine property, BR_ENGINE, platform override directory, bundled library.")
                .define("enginePath", "");
        numThreads = b.comment("Native engine worker budget per dimension.")
                .defineInRange("numThreads", 1, 1, 256);
        bucklingEnabled = b.comment("Request engine eigen buckling; the engine reports eligibility per island.")
                .define("bucklingEnabled", true);
        bucklingDofBudget = b.comment("Engine eigen DOF budget per island; 0 means no host-imposed DOF limit.")
                .defineInRange("bucklingDofBudget", 2400, 0, 10_000_000);

        analysisEnabled = b
                .comment("Master switch. Off means no library is discovered, unpacked or loaded.")
                .define("analysisEnabled", true);

        requestTimeoutMs = b
                .comment("Timeout revokes a native result; native work can only close safely after it returns.")
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
