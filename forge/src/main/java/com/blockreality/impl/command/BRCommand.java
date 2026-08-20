package com.blockreality.impl.command;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.StressStation;
import com.blockreality.core.sidecar.SidecarClient;
import com.blockreality.impl.server.SidecarLocator;
import com.blockreality.impl.server.StructureManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Locale;

/**
 * {@code /br} — the answer to "why is nothing happening?".
 *
 * <p>Everything the analysis pipeline knows is reachable from one command: whether the
 * engine was found and where it was looked for, what the current revision is, how many
 * blocks are in the model, and what the last result said. Without this, a missing binary
 * and an unstressed structure look identical from inside the game.
 *
 * <p>Read-only except for {@code resolve} and {@code reset}, both of which only ask for
 * work to be redone. Nothing here can change the world.
 */
public final class BRCommand {

    private BRCommand() { }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("br")
                .then(Commands.literal("status").executes(c -> status(c.getSource())))
                .then(Commands.literal("members").executes(c -> members(c.getSource())))
                .then(Commands.literal("section")
                        .then(Commands.argument("member", IntegerArgumentType.integer(1))
                                .executes(c -> section(c.getSource(),
                                        IntegerArgumentType.getInteger(c, "member")))))
                .then(Commands.literal("resolve")
                        // Available to everyone: it only asks for a recomputation.
                        .executes(c -> resolve(c.getSource())))
                .then(Commands.literal("scan")
                        .executes(c -> scan(c.getSource(), 4))
                        .then(Commands.argument("chunkRadius", IntegerArgumentType.integer(0, 16))
                                .executes(c -> scan(c.getSource(),
                                        IntegerArgumentType.getInteger(c, "chunkRadius")))))
                .then(Commands.literal("reset")
                        .requires(s -> s.hasPermission(2))
                        .executes(c -> reset(c.getSource())))
                .executes(c -> status(c.getSource())));
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

        // Where it looked, always — a wrong path is the single most likely first-run
        // problem and guessing at it from a one-word status is miserable.
        for (String l : SidecarLocator.describe(m.engineLocation()).split("\n")) {
            line(src, "  " + l, ChatFormatting.DARK_GRAY);
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
