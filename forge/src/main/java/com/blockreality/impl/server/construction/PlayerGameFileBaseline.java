package com.blockreality.impl.server.construction;

import com.blockreality.impl.BlockRealityMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

@Mod.EventBusSubscriber(modid="blockreality")
final class PlayerGameFileBaseline {
    private record State(Path directory,byte[] digest,boolean represented) { }
    private static final Map<UUID,State> STATES=new HashMap<>();
    private PlayerGameFileBaseline() { }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void loaded(PlayerEvent.LoadFromFile event) {
        observe(event.getEntity(),event.getPlayerDirectory().toPath(),event.getPlayerUUID());
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void saved(PlayerEvent.SaveToFile event) {
        observe(event.getEntity(),event.getPlayerDirectory().toPath(),event.getPlayerUUID());
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) forget(player.getUUID());
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { STATES.clear(); }

    private static void observe(net.minecraft.world.entity.player.Player entity,Path directory,String fileId) {
        if (!(entity instanceof ServerPlayer player)) return;
        UUID actor=player.getUUID();
        STATES.remove(actor);
        try {
            if (!player.getServer().isSameThread() || !actor.toString().equals(fileId)
                    || !normalize(directory).equals(normalize(player.getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR))))
                throw new IOException("Foreign player save boundary");
            var snapshot=new PlayerFileParticipant(directory).capture(actor);
            recordBoundary(directory,actor,snapshot,LivePlayerInventory.serializedPlayer(player));
        } catch(IOException|RuntimeException invalid) {
            BlockRealityMod.LOG.warn("Player baseline boundary unavailable for {}: {}",actor,invalid.toString());
        }
    }
    static void recordBoundary(Path directory,UUID actor,PlayerFileParticipant.Snapshot snapshot,CompoundTag live) throws IOException {
        Path location=normalize(directory);
        byte[] digest=snapshot.rawDigest();
        STATES.put(actor,new State(location,digest,false));
        LivePlayerInventory.requirePreservableStructure(snapshot.data(),live);
        STATES.put(actor,new State(location,digest,true));
    }
    static void requireTrusted(Path directory,UUID actor,PlayerFileParticipant.Snapshot snapshot) throws IOException {
        State state=STATES.get(actor);
        if (state==null || !state.directory.equals(normalize(directory)) || !state.represented
                || !Arrays.equals(state.digest,snapshot.rawDigest()))
            throw new IOException("Player file has no consistent game-owned baseline");
    }
    static void acknowledgeWrite(Path directory,UUID actor,PlayerFileParticipant.Snapshot snapshot,boolean live) throws IOException {
        State state=STATES.get(actor);
        if (state!=null && !state.directory.equals(normalize(directory))) throw new IOException("Foreign player baseline directory");
        if ((state==null && live) || (state!=null && state.represented))
            STATES.put(actor,new State(normalize(directory),snapshot.rawDigest(),true));
    }
    static void forget(UUID actor) { STATES.remove(actor); }
    private static Path normalize(Path directory) { return directory.toAbsolutePath().normalize(); }
}
