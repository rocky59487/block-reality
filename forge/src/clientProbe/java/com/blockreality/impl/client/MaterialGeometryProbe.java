package com.blockreality.impl.client;

import com.blockreality.impl.block.StructuralBlock;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.*;
import net.minecraftforge.registries.ForgeRegistries;

/** Opt-in observations and labelled vanilla client interaction in the owned loopback world only. */
@OnlyIn(Dist.CLIENT)
final class MaterialGeometryProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    static boolean handle(Minecraft mc, Path out, JsonObject command) throws Exception {
        String action = command.get("action").getAsString();
        if (!Set.of("models", "inspect", "interact", "crouch", "inventory").contains(action)) return false;
        Set<String> keys = new HashSet<>(Set.of("id", "action", "name"));
        if (action.equals("crouch") || action.equals("inventory")) keys.add("down");
        if (!command.keySet().equals(keys)) throw new IllegalArgumentException("geometry control shape");
        String name = command.get("name").getAsString();
        if (!name.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("geometry receipt name");
        Path receipt = out.resolve(name + ".json");
        if (Files.exists(receipt)) throw new IllegalArgumentException("geometry receipt already exists");
        if (mc.level == null || mc.player == null || mc.gameMode == null || mc.getCurrentServer() == null
                || !mc.getCurrentServer().ip.equals("127.0.0.1:25594")) throw new IllegalStateException("owned client required");
        JsonObject result = new JsonObject(); result.add("request", command.deepCopy());
        result.addProperty("scope", "opt-in real client; observations or programmatic vanilla interaction; no result injection");
        switch (action) {
            case "models" -> result.add("products", models(mc));
            case "inspect" -> { mc.gameRenderer.pick(1); result.add("observed", observation(mc)); }
            case "crouch" -> mc.options.keyShift.setDown(command.get("down").getAsBoolean());
            case "inventory" -> mc.setScreen(command.get("down").getAsBoolean() ? new InventoryScreen(mc.player) : null);
            case "interact" -> {
                if (mc.screen != null || !mc.gameMode.hasInfiniteItems()) throw new IllegalStateException("creative world interaction required");
                mc.gameRenderer.pick(1);
                if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)
                    throw new IllegalStateException("real crosshair must hit a block");
                BlockPos p = hit.getBlockPos();
                if (p.getX() < 40 || p.getX() > 63 || p.getY() < 198 || p.getY() > 205 || p.getZ() < 40 || p.getZ() > 63)
                    throw new IllegalStateException("outside geometry interaction fixture");
                result.add("before", observation(mc));
                var used = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                result.addProperty("entryPoint", "MultiPlayerGameMode.useItemOn(real mc.hitResult)");
                result.addProperty("returned", used.name());
                if (used.shouldSwing()) mc.player.swing(InteractionHand.MAIN_HAND);
                result.add("afterClientPrediction", observation(mc));
            }
            default -> throw new AssertionError(action);
        }
        Files.writeString(receipt, JSON.toJson(result), StandardOpenOption.CREATE_NEW);
        return true;
    }

    static JsonObject observation(Minecraft mc) {
        JsonObject r = new JsonObject();
        if (mc.player == null || mc.level == null) return r;
        r.addProperty("shift", mc.player.isShiftKeyDown());
        r.addProperty("mainHand", ForgeRegistries.ITEMS.getKey(mc.player.getMainHandItem().getItem()).toString());
        r.addProperty("hitType", mc.hitResult == null ? "NONE" : mc.hitResult.getType().name());
        if (mc.hitResult instanceof BlockHitResult hit) {
            BlockPos p = hit.getBlockPos(); BlockState state = mc.level.getBlockState(p);
            r.add("position", array(p.getX(), p.getY(), p.getZ()));
            r.add("hitLocation", array(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z));
            r.addProperty("face", hit.getDirection().getName());
            r.addProperty("block", ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString());
            if (state.getBlock() instanceof StructuralBlock) r.addProperty("axis", state.getValue(StructuralBlock.AXIS).getSerializedName());
            var shape = state.getShape(mc.level, p); var collision = state.getCollisionShape(mc.level, p);
            r.add("shape", shape.isEmpty() ? JsonNull.INSTANCE : box(shape.bounds()));
            r.add("collision", collision.isEmpty() ? JsonNull.INSTANCE : box(collision.bounds()));
        }
        return r;
    }

    private static JsonArray models(Minecraft mc) {
        JsonArray products = new JsonArray();
        ForgeRegistries.BLOCKS.getValues().stream().filter(b -> b instanceof StructuralBlock)
                .sorted(Comparator.comparing(b -> ForgeRegistries.BLOCKS.getKey(b).toString())).forEach(block -> {
            JsonObject product = new JsonObject(); product.addProperty("id", ForgeRegistries.BLOCKS.getKey(block).toString());
            JsonArray states = new JsonArray();
            for (var axis : StructuralBlock.Axis.values()) {
                var state = block.defaultBlockState().setValue(StructuralBlock.AXIS, axis);
                JsonObject s = new JsonObject(); s.addProperty("axis", axis.getSerializedName());
                s.add("baked", quads(mc.getBlockRenderer().getBlockModel(state), state));
                s.add("shape", box(state.getShape(mc.level, BlockPos.ZERO).bounds()));
                s.add("collision", box(state.getCollisionShape(mc.level, BlockPos.ZERO).bounds()));
                states.add(s);
            }
            product.add("states", states);
            product.add("item", quads(mc.getItemRenderer().getModel(new ItemStack(block), mc.level, mc.player, 0), null));
            products.add(product);
        });
        return products;
    }

    private static JsonObject quads(BakedModel model, BlockState state) {
        JsonObject result = new JsonObject(); JsonArray quads = new JsonArray();
        double[] bounds = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        List<Direction> sides = new ArrayList<>(Arrays.asList(Direction.values())); sides.add(null);
        for (Direction side : sides) for (var quad : model.getQuads(state, side, RandomSource.create(42))) {
            int[] vertices = quad.getVertices(); int stride = vertices.length / 4;
            JsonArray points = new JsonArray();
            for (int vertex = 0; vertex < 4; vertex++) {
                JsonArray point = new JsonArray();
                for (int axis = 0; axis < 3; axis++) {
                    float value = Float.intBitsToFloat(vertices[vertex*stride+axis]); point.add(value);
                    bounds[axis] = Math.min(bounds[axis], value); bounds[axis+3] = Math.max(bounds[axis+3], value);
                }
                points.add(point);
            }
            JsonObject q = new JsonObject(); q.add("vertices", points);
            q.addProperty("face", quad.getDirection().getName()); q.addProperty("cull", side == null ? "none" : side.getName());
            q.addProperty("sprite", quad.getSprite().contents().name().toString()); quads.add(q);
        }
        result.add("quads", quads); result.add("bounds", quads.isEmpty() ? JsonNull.INSTANCE : array(bounds));
        result.addProperty("particle", model.getParticleIcon().contents().name().toString());
        return result;
    }
    private static JsonArray array(double... values) { JsonArray a = new JsonArray(); for (double v : values) a.add(v); return a; }
    private static JsonArray box(AABB b) { return array(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ); }
}
