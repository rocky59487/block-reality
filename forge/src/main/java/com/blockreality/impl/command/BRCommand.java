package com.blockreality.impl.command;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.StressStation;
import com.blockreality.core.sidecar.SidecarClient;
import com.blockreality.impl.server.SidecarLocator;
import com.blockreality.impl.server.StructureManager;
import com.blockreality.impl.block.StructuralBlock;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Locale;
import java.util.Map;

/**
 * {@code /br} — the answer to "why is nothing happening?".
 *
 * <p>Everything the analysis pipeline knows is reachable from one command: whether the
 * engine was found and where it was looked for, what the current revision is, how many
 * blocks are in the model, and what the last result said. Without this, a missing binary
 * and an unstressed structure look identical from inside the game.
 *
 * <p>Read-only subcommands (status/members/section/loads) are open to everyone; the
 * rest require operator level 2 — see {@link BrPermissions} for the table and the
 * reasoning per command. {@code scan} walks up to 1089 chunks on the server thread,
 * {@code load} applies arbitrary force vectors to the shared model, and neither is
 * something an anonymous survival player should hold on a public server (#45).
 * Nothing here places or breaks a block.
 */
public final class BRCommand {

    private BRCommand() { }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    /** A literal whose permission level comes from the table — never inline (#45). */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> lit(String name) {
        int level = BrPermissions.required(name);
        return Commands.literal(name).requires(s -> s.hasPermission(level));
    }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("br")
                .then(lit("status").executes(c -> status(c.getSource())))
                .then(lit("members").executes(c -> members(c.getSource())))
                .then(lit("section")
                        .then(Commands.argument("member", IntegerArgumentType.integer(1))
                                .executes(c -> section(c.getSource(),
                                        IntegerArgumentType.getInteger(c, "member")))))
                .then(lit("resolve")
                        .executes(c -> resolve(c.getSource())))
                .then(lit("scan")
                        .executes(c -> scan(c.getSource(), 4))
                        .then(Commands.argument("chunkRadius", IntegerArgumentType.integer(0, 16))
                                .executes(c -> scan(c.getSource(),
                                        IntegerArgumentType.getInteger(c, "chunkRadius")))))
                // Arbitrary test loads, in kN because that is the unit an engineering
                // reader thinks in (the wire stays in newtons).
                .then(lit("load")
                        .then(Commands.argument("fxKn", DoubleArgumentType.doubleArg(-1e6, 1e6))
                                .then(Commands.argument("fyKn", DoubleArgumentType.doubleArg(-1e6, 1e6))
                                        .then(Commands.argument("fzKn", DoubleArgumentType.doubleArg(-1e6, 1e6))
                                                .executes(c -> load(c.getSource(),
                                                        DoubleArgumentType.getDouble(c, "fxKn"),
                                                        DoubleArgumentType.getDouble(c, "fyKn"),
                                                        DoubleArgumentType.getDouble(c, "fzKn")))))))
                // "unload all" inherits the parent literal's gate: Brigadier checks
                // requires() on every node along the executed path.
                .then(lit("unload")
                        .executes(c -> load(c.getSource(), 0, 0, 0))
                        .then(Commands.literal("all").executes(c -> unloadAll(c.getSource()))))
                .then(lit("loads").executes(c -> loads(c.getSource())))
                .then(lit("reset")
                        .executes(c -> reset(c.getSource())))
                .executes(c -> status(c.getSource())));
    }

    /**
     * Applies (or with a zero vector clears) a test load on the block the player is
     * looking at. The block must be structural: a load anywhere else would be refused
     * by the engine's fail-closed check anyway, and telling the player at the point of
     * aim beats a refusal three ticks later.
     */
    private static int load(CommandSourceStack src, double fxKn, double fyKn, double fzKn) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            line(src, "A player has to aim this command.", ChatFormatting.RED);
            return 0;
        }
        HitResult hit = player.pick(20.0, 0.0f, false);
        if (!(hit instanceof BlockHitResult bh)
                || !(src.getLevel().getBlockState(bh.getBlockPos()).getBlock() instanceof StructuralBlock)) {
            line(src, Component.translatable("br.load.not_structural").getString(), ChatFormatting.YELLOW);
            return 0;
        }
        BlockPos pos = bh.getBlockPos();
        boolean present = managerFor(src).setLoad(pos, fxKn * 1000.0, fyKn * 1000.0, fzKn * 1000.0);
        if (present) {
            line(src, String.format(Locale.ROOT, "Load at %d %d %d: (%.1f, %.1f, %.1f) kN",
                    pos.getX(), pos.getY(), pos.getZ(), fxKn, fyKn, fzKn), ChatFormatting.GREEN);
        } else {
            line(src, String.format(Locale.ROOT, "Load cleared at %d %d %d",
                    pos.getX(), pos.getY(), pos.getZ()), ChatFormatting.GRAY);
        }
        return 1;
    }

    private static int unloadAll(CommandSourceStack src) {
        int n = managerFor(src).clearAllLoads();
        line(src, n + " test load" + (n == 1 ? "" : "s") + " removed.", ChatFormatting.GRAY);
        return n;
    }

    private static int loads(CommandSourceStack src) {
        Map<BlockPos, double[]> all = managerFor(src).loads();
        if (all.isEmpty()) {
            line(src, "No test loads. /br load <fx> <fy> <fz> (kN) applies one to the block you aim at.",
                    ChatFormatting.GRAY);
            return 0;
        }
        line(src, "Test loads (kN):", ChatFormatting.AQUA);
        all.forEach((pos, f) -> line(src, String.format(Locale.ROOT,
                "  %d %d %d   (%.1f, %.1f, %.1f)",
                pos.getX(), pos.getY(), pos.getZ(),
                f[0] / 1000.0, f[1] / 1000.0, f[2] / 1000.0), ChatFormatting.GRAY));
        return all.size();
    }

    private static StructureManager managerFor(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        return StructureManager.of(level);
    }

    private static int status(CommandSourceStack src) {
        StructureManager m = managerFor(src);
        SidecarClient.Status s = m.engineStatus();

        line(src, "Block Reality", ChatFormatting.AQUA);
        line(src, "  dimension       " + m.dimension().location(), ChatFormatting.GRAY);
        line(src, "  engine          " + s
                        + (s == SidecarClient.Status.READY
                                ? "   (transport: " + m.engineTransport() + ")" : ""),
                s == SidecarClient.Status.READY ? ChatFormatting.GREEN
                        : s == SidecarClient.Status.DISABLED ? ChatFormatting.RED
                        : ChatFormatting.YELLOW);

        // Where it looked — a wrong path is the single most likely first-run problem.
        // OP only: the search list spells out server filesystem paths (user names,
        // drive layout), which a non-privileged player has no business reading (#45).
        if (src.hasPermission(BrPermissions.LEVEL_OP)) {
            for (String l : SidecarLocator.describe(m.engineLocation()).split("\n")) {
                line(src, "  " + l, ChatFormatting.DARK_GRAY);
            }
        }

        line(src, "  revision        " + m.gate().current().value()
                + "   (rejected as stale: " + m.gate().rejectedCount() + ")", ChatFormatting.GRAY);
        line(src, "  blocks          " + m.structuralBlockCount()
                + "   test loads: " + m.loadedBlockCount(), ChatFormatting.GRAY);

        AnalysisResult r = m.latest();
        if (r == null) {
            line(src, "  last result     none yet", ChatFormatting.GRAY);
            return 1;
        }
        if (!r.ok()) {
            line(src, "  last result     FAILED — " + r.diagnostic(), ChatFormatting.RED);
        } else if (r.allSingular()) {
            // Not a failure and not a safe structure: nothing is holding it up.
            line(src, "  last result     MECHANISM — " + r.diagnostic(), ChatFormatting.YELLOW);
        } else if (!r.isUsable()) {
            // ok, not singular, yet nothing came back. Printing the old green
            // "0 members, max D/C 0.0000" here dressed an empty answer up as a safe
            // one (#53); an empty answer is a diagnostic, not a verdict.
            line(src, "  last result     EMPTY — engine returned no members or facets"
                    + (r.diagnostic().isEmpty() ? "" : " — " + r.diagnostic()),
                    ChatFormatting.YELLOW);
        } else {
            line(src, String.format(Locale.ROOT,
                            "  last result     %d members, %d plate facets, max D/C %.4f  (%s)%s",
                            r.members().size(), r.shells().size(), r.maxDc(),
                            r.governingKind().isEmpty() ? "-" : r.governingKind() + " #" + r.governing(),
                            r.maxDc() > 1.0 ? "  OVER CAPACITY" : ""),
                    r.maxDc() > 1.0 ? ChatFormatting.RED : ChatFormatting.GREEN);
            line(src, String.format(Locale.ROOT, "  structures      %d solved, %d unrestrained",
                            r.islands(), r.singularIslands()),
                    r.singularIslands() > 0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
            // The engine's own force balance, recomputed from geometry rather than read
            // back out of the load vector. It is here because a number that is only
            // checked in the test suite is a number nobody checks in the field.
            line(src, String.format(Locale.ROOT, "  equilibrium     residual %.3e",
                            r.equilibriumResidual()),
                    r.equilibriumResidual() > 1e-8 ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
            // Stability, reported next to strength and never folded into it. A slender
            // column reaches its buckling load at a stress the D/C line calls comfortable.
            // Absent means the structure carries no compression that could buckle it, which
            // is a real state and not a missing number.
            if (r.bucklingFactor() > 0) {
                line(src, String.format(Locale.ROOT,
                                "  buckling        lambda_cr %.3f%s   (linear onset, an upper bound)",
                                r.bucklingFactor(),
                                r.bucklingCritical() ? "   ALREADY UNSTABLE" : ""),
                        r.bucklingCritical() ? ChatFormatting.RED : ChatFormatting.GRAY);
            }
            if (!r.unassigned().isEmpty()) {
                line(src, "  unassigned      " + r.unassigned().size()
                        + " blocks formed no element", ChatFormatting.YELLOW);
            }
        }
        return 1;
    }

    private static int members(CommandSourceStack src) {
        AnalysisResult r = managerFor(src).latest();
        if (r == null || !r.isUsable()) {
            line(src, "No usable analysis. Try /br status.", ChatFormatting.YELLOW);
            return 0;
        }
        line(src, "members  " + r.members().size()
                + "     plate facets  " + r.shells().size(), ChatFormatting.AQUA);
        for (MemberSnapshot m : r.members()) {
            String gov = m.governingStation() >= 0 && m.governingStation() < m.stations().size()
                    ? String.format(Locale.ROOT, " at x=%.0fmm", m.stations().get(m.governingStation()).xMm())
                    : "";
            line(src, String.format(Locale.ROOT,
                            "  #%d  %s %s  L=%.0fmm  D/C=%.4f  %s%s  peak %.2f MPa",
                            m.id(), m.material(), m.section(), m.lengthMm(), m.dc(),
                            m.governingFibre(), gov, m.peakMagnitudeMpa()),
                    m.isOverloaded() ? ChatFormatting.RED : ChatFormatting.GRAY);
        }
        // Plates print dcRaw alongside dc. The two differ only where the support-moment
        // recovery fired, and seeing both is the only way to tell a recovered demand from
        // a raw one without reading the source.
        for (com.blockreality.api.ShellSnapshot sh : r.shells()) {
            line(src, String.format(Locale.ROOT,
                            "  P%d  %s  t=%.0fmm  D/C=%.4f (raw %.4f%s)  %s face  peak %.2f MPa",
                            sh.id(), sh.plate(), sh.thicknessMm(), sh.dc(), sh.dcRaw(),
                            sh.edgeRecovered() ? ", edge recovered" : "",
                            sh.governingTopFace() ? "top" : "bottom", sh.peakMpa()),
                    sh.dc() > 1.0 ? ChatFormatting.RED : ChatFormatting.GRAY);
        }
        return 1;
    }

    /**
     * The whole stress profile of one member, as text.
     *
     * <p>Exists so the picture can be checked against something. "The colours look wrong"
     * and "the colours are wrong" are different claims, and only numbers with the words
     * TENSION and COMPRESSION printed next to them can tell them apart.
     */
    private static int section(CommandSourceStack src, int memberId) {
        AnalysisResult r = managerFor(src).latest();
        if (r == null || !r.isUsable()) {
            line(src, "No usable analysis. Try /br status.", ChatFormatting.YELLOW);
            return 0;
        }
        var found = r.member(memberId);
        if (found.isEmpty()) {
            line(src, "No member #" + memberId + ". Try /br members.", ChatFormatting.YELLOW);
            return 0;
        }
        MemberSnapshot m = found.get();
        line(src, String.format(Locale.ROOT, "member #%d  %s %s  L=%.0fmm  D/C=%.4f",
                m.id(), m.material(), m.section(), m.lengthMm(), m.dc()), ChatFormatting.AQUA);
        line(src, "  tension is positive; a cantilever hogs (top tension), a beam on two "
                + "supports sags (top compression) — both are correct", ChatFormatting.DARK_GRAY);
        line(src, "     x(m)      top        bottom     neutral axis", ChatFormatting.GRAY);

        for (StressStation s : m.stations()) {
            var top = s.fibre("TOP_Y");
            var bot = s.fibre("BOT_Y");
            if (top.isEmpty() || bot.isEmpty()) continue;
            double t = top.get().sigmaMpa();
            double b = bot.get().sigmaMpa();
            String na = s.naOffsetYMm().map(v -> String.format(Locale.ROOT, "%+.1fmm", v)).orElse("none");
            line(src, String.format(Locale.ROOT, "  %6.2f  %+8.3f %-4s %+8.3f %-4s  %s",
                            s.xMm() / 1000.0,
                            t, t > 0 ? "TEN" : t < 0 ? "COM" : "-",
                            b, b > 0 ? "TEN" : b < 0 ? "COM" : "-",
                            na),
                    s.xMm() == governingX(m) ? ChatFormatting.WHITE : ChatFormatting.GRAY);
        }
        return 1;
    }

    private static double governingX(MemberSnapshot m) {
        int i = m.governingStation();
        return i >= 0 && i < m.stations().size() ? m.stations().get(i).xMm() : -1;
    }

    private static int scan(CommandSourceStack src, int chunkRadius) {
        // Blocks placed by /setblock, by a world edit tool, or present before this build
        // was installed never raised a place event, so nothing knows about them.
        StructureManager m = managerFor(src);
        int found = m.rescan(src.getLevel(), BlockPos.containing(src.getPosition()), chunkRadius);
        line(src, "Scanned " + (2 * chunkRadius + 1) + "x" + (2 * chunkRadius + 1)
                + " chunks: " + found + " structural blocks now tracked ("
                + m.structuralBlockCount() + " total).", ChatFormatting.GREEN);
        return found;
    }

    private static int resolve(CommandSourceStack src) {
        managerFor(src).requestResolve();
        line(src, "Re-analysis requested.", ChatFormatting.GREEN);
        return 1;
    }

    private static int reset(CommandSourceStack src) {
        StructureManager m = managerFor(src);
        m.resetEngine();
        line(src, "Engine reset; it will be started again on the next tick.", ChatFormatting.GREEN);
        return 1;
    }

    private static void line(CommandSourceStack src, String text, ChatFormatting colour) {
        src.sendSuccess(() -> Component.literal(text).withStyle(colour), false);
    }
}
