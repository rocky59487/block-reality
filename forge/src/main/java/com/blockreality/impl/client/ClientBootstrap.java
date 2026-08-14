package com.blockreality.impl.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-side entry point, invoked through {@code DistExecutor} so a dedicated server
 * never loads this class or anything it references.
 *
 * <p>Class loading, not just execution, is what has to be kept off the server. A common
 * class that merely mentions a client type in a method signature can trip a
 * {@code NoClassDefFoundError} during verification, long before that method would run.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientBootstrap {

    private ClientBootstrap() { }

    public static void init() {
        // The renderer and HUD register themselves through @Mod.EventBusSubscriber with
        // Dist.CLIENT, so there is nothing to wire here yet. The hook exists because
        // keybindings and model registration land here next, and adding it later means
        // editing the mod entry point again.
    }
}
