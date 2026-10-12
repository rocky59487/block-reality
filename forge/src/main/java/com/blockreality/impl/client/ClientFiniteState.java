package com.blockreality.impl.client;

import com.blockreality.impl.BlockRealityMod;
import com.blockreality.impl.net.FiniteQualificationPacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The client's newest finite-analysis qualification, keyed to one dimension. Same trust
 * model as {@link ClientStressState}: the packet already passed wire validation, and the
 * state is only accepted from the live connection for the dimension on screen.
 *
 * <p>The values are the native verdicts verbatim — statuses stay raw wire codes and the
 * HUD names them; nothing is recomputed into a safety claim.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientFiniteState {

    private ClientFiniteState() { }

    private static FiniteQualificationPacket qualification;
    private static String dimension = "";
    private static net.minecraft.network.Connection connection;

    /** The newest accepted qualification for the current dimension, or null. */
    public static FiniteQualificationPacket qualification() {
        Minecraft mc = Minecraft.getInstance();
        if (qualification == null || mc.level == null
                || !dimension.equals(mc.level.dimension().location().toString())) return null;
        return qualification;
    }

    public static void accept(FiniteQualificationPacket p, net.minecraft.network.Connection sender) {
        Minecraft mc = Minecraft.getInstance();
        if (!p.valid() || mc.level == null || mc.getConnection() == null
                || sender != mc.getConnection().getConnection()) return;
        if (!p.dimension().equals(mc.level.dimension().location().toString())) {
            BlockRealityMod.LOG.debug("dropping finite qualification for {} while in {}",
                    p.dimension(), mc.level.dimension().location());
            return;
        }
        if (connection != sender) { clear(); connection = sender; }
        qualification = p;
        dimension = p.dimension();
    }

    /** Logout or a new connection retires the qualification with the rest of the display. */
    public static void clear() { qualification = null; dimension = ""; connection = null; }

    /** Travel/respawn retires the binding; old data never follows the player. */
    public static void leaveDimension() { qualification = null; dimension = ""; }
}
