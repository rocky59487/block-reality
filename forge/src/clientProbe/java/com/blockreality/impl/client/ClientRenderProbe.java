package com.blockreality.impl.client;

import com.blockreality.api.ScanMode;
import com.blockreality.impl.net.AnalysisUpdatePacket;
import com.google.gson.*;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;

/** Isolated development-client capture only. Never part of ordinary source sets or jars. */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "blockreality", value = Dist.CLIENT)
public final class ClientRenderProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path out;
    private static long started, handled;
    private static int ticks, warmFrames;
    private static boolean joined;
    private static volatile boolean writing;
    private static JsonObject pending;
    private static CompletableFuture<Void> reload = CompletableFuture.completedFuture(null);

    private ClientRenderProbe() { }

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Boolean.getBoolean("br.clientRenderProbe")) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (out == null) {
                out = Path.of(System.getProperty("br.clientProbeOutput")).toAbsolutePath();
                if (!Files.readString(out.resolve("CRP_OWNED")).strip().equals("block-reality-client-render-probe-v1"))
                    throw new IllegalStateException("capture directory is not probe-owned");
                started = System.nanoTime();
                mc.options.pauseOnLostFocus = false;
                mc.options.framerateLimit().set(60);
                mc.options.enableVsync().set(false);
                mc.getWindow().setTitle("Block Reality isolated render probe");
            }
            if (System.nanoTime() - started > 600_000_000_000L) throw new IllegalStateException("ten-minute client deadline");
            if (!joined && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                joined = true;
                String address = "127.0.0.1:25594";
                ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(address),
                        new ServerData("Block Reality isolated render probe", address, false), false);
            }
            if (++ticks % 20 == 0) {
                JsonObject status = state(mc);
                status.addProperty("handled", handled);
                status.addProperty("writing", writing);
                Files.writeString(out.resolve("state.next"), JSON.toJson(status));
                Files.move(out.resolve("state.next"), out.resolve("state.json"),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            if (pending != null || writing || !Files.exists(out.resolve("control.json"))) return;
            String raw = Files.readString(out.resolve("control.json"));
            if (raw.length() > 4096) throw new IllegalArgumentException("oversized control");
            JsonObject command = JsonParser.parseString(raw).getAsJsonObject();
            long id = command.get("id").getAsLong();
            if (id == handled) return;
            if (id < 1 || id < handled) throw new IllegalArgumentException("control order");
            String action = command.get("action").getAsString();
            if (action.equals("exit") && command.keySet().equals(Set.of("id", "action"))) {
                handled = id; mc.stop(); return;
            }
            if (!action.equals("capture") || !command.keySet().equals(Set.of("id", "action", "name",
                    "width", "height", "scale", "language", "mode", "worldRevision", "kind")))
                throw new IllegalArgumentException("unknown control shape");
            String name = command.get("name").getAsString(), language = command.get("language").getAsString();
            int width = command.get("width").getAsInt(), height = command.get("height").getAsInt();
            int scale = command.get("scale").getAsInt();
            if (!name.matches("[a-z0-9_-]{1,64}") || !(language.equals("en_us") || language.equals("zh_tw"))
                    || !((width == 1280 && height == 720) || (width == 1920 && height == 1080))
                    || scale < 1 || scale > 3 || command.get("worldRevision").getAsLong() < 0)
                throw new IllegalArgumentException("unsupported capture settings");
            ScanMode mode = ScanMode.valueOf(command.get("mode").getAsString());
            if (!mode.isDemoV0()) throw new IllegalArgumentException("unsupported capture mode");
            AnalysisUpdatePacket.Kind.valueOf(command.get("kind").getAsString());
            if (Files.exists(out.resolve("screenshots").resolve(name + ".png")) || Files.exists(out.resolve(name + ".json")))
                throw new IllegalArgumentException("capture already exists");
            handled = id; pending = command; warmFrames = 0;
            mc.getWindow().setWindowed(width, height);
            mc.options.guiScale().set(scale); mc.resizeDisplay();
            while (ClientStressState.mode() != mode) ClientStressState.cycleMode();
            if (!mc.getLanguageManager().getSelected().equals(language)) {
                mc.getLanguageManager().setSelected(language); mc.options.languageCode = language;
                reload = mc.reloadResourcePacks();
            }
        } catch (Throwable failure) { fail(mc, failure); }
    }

    @SubscribeEvent public static void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending == null || writing) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (!reload.isDone()) return;
            reload.join();
            if (mc.player == null || mc.level == null || mc.screen != null || mc.getOverlay() != null
                    || announcedRevision() != pending.get("worldRevision").getAsLong()
                    || !stateKind().equals(pending.get("kind").getAsString())
                    || mc.getWindow().getWidth() != pending.get("width").getAsInt()
                    || mc.getWindow().getHeight() != pending.get("height").getAsInt()) { warmFrames = 0; return; }
            if (++warmFrames < 40) return;
            String name = pending.get("name").getAsString();
            JsonObject receipt = state(mc); receipt.add("request", pending.deepCopy());
            pending = null; writing = true;
            Screenshot.grab(out.toFile(), name + ".png", mc.getMainRenderTarget(), message -> {
                try {
                    Path png = out.resolve("screenshots").resolve(name + ".png");
                    if (!Files.isRegularFile(png) || Files.size(png) == 0) throw new IllegalStateException("screenshot failed: " + message.getString());
                    receipt.addProperty("png_bytes", Files.size(png));
                    Files.writeString(out.resolve(name + ".json"), JSON.toJson(receipt), StandardOpenOption.CREATE_NEW);
                } catch (Throwable failure) { mc.execute(() -> fail(mc, failure)); }
                finally { writing = false; }
            });
        } catch (Throwable failure) { fail(mc, failure); }
    }

    private static long announcedRevision() throws ReflectiveOperationException {
        var field = ClientStressState.class.getDeclaredField("pendingRevision");
        field.setAccessible(true); return field.getLong(null);
    }
    private static String stateKind() {
        // Production clears the notice when accepting a result. Observe the actual
        // summary flag as well; a null notice by itself does not mean RESULT.
        var notice = ClientStressState.notice();
        return notice != null ? notice.name() : ClientStressState.hasData() ? "RESULT" : "NONE";
    }
    private static JsonObject state(Minecraft mc) throws ReflectiveOperationException {
        JsonObject result = new JsonObject();
        result.addProperty("connected", mc.player != null && mc.level != null);
        result.addProperty("screen", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
        result.addProperty("worldRevision", announcedRevision());
        result.addProperty("resultRevision", ClientStressState.revision());
        result.addProperty("kind", stateKind());
        result.addProperty("notice", String.valueOf(ClientStressState.notice()));
        result.addProperty("hasData", ClientStressState.hasData());
        result.addProperty("stale", ClientStressState.stale());
        result.addProperty("members", ClientStressState.totalMembers());
        result.addProperty("shells", ClientStressState.totalShells());
        result.addProperty("maxDc", ClientStressState.maxDc());
        result.addProperty("width", mc.getWindow().getWidth()); result.addProperty("height", mc.getWindow().getHeight());
        result.addProperty("guiWidth", mc.getWindow().getGuiScaledWidth()); result.addProperty("guiHeight", mc.getWindow().getGuiScaledHeight());
        result.addProperty("guiScale", mc.getWindow().getGuiScale());
        result.addProperty("language", mc.getLanguageManager().getSelected());
        result.addProperty("mode", ClientStressState.mode().name());
        result.addProperty("glRenderer", GL11.glGetString(GL11.GL_RENDERER));
        result.addProperty("glVersion", GL11.glGetString(GL11.GL_VERSION));
        return result;
    }
    private static void fail(Minecraft mc, Throwable failure) {
        failure.printStackTrace();
        try {
            if (out != null && !Files.exists(out.resolve("fatal.txt")))
                Files.writeString(out.resolve("fatal.txt"), failure.toString(), StandardOpenOption.CREATE_NEW);
        } catch (Exception secondary) { secondary.printStackTrace(); }
        pending = null; mc.stop();
    }
}
