package com.blockreality.impl.client;

import com.blockreality.core.engine.GameVocabulary;
import com.blockreality.core.engine.ProductForm;
import com.google.gson.*;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.ElementsModel;
import net.minecraftforge.client.model.geometry.IGeometryLoader;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Bakes ordinary atlas quads from the same declared bounds used by block interaction. */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "blockreality", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ProductModelLoader implements IGeometryLoader<ElementsModel> {
    @SubscribeEvent public static void register(ModelEvent.RegisterGeometryLoaders event) {
        event.register("product", new ProductModelLoader());
    }

    @Override public ElementsModel read(JsonObject json, JsonDeserializationContext context) {
        boolean undeclared = json.has("undeclared") && json.get("undeclared").getAsBoolean();
        ProductForm form = undeclared ? ProductForm.UNRESOLVED : ProductForm.of(GameVocabulary.geometry(
                json.get("material").getAsString(), json.get("section").getAsString()), 2);
        JsonArray elements = new JsonArray();
        if (form.resolved()) {
            var b = form.box();
            JsonObject faces = new JsonObject();
            for (var direction : net.minecraft.core.Direction.values()) {
                int axis = direction.getAxis().ordinal();
                var face = new JsonObject();
                face.addProperty("texture", axis == 2 ? "#end" : "#side");
                if (direction.getAxisDirection().getStep() > 0 ? b.max(axis) == 1 : b.min(axis) == 0)
                    face.addProperty("cullface", direction.getName());
                faces.add(direction.getName(), face);
            }
            elements.add(element(new double[]{b.minX(), b.minY(), b.minZ()}, new double[]{b.maxX(), b.maxY(), b.maxZ()}, faces));
        } else {
            // Tile each cell face so the ochre ! is in the surface, with no extra silhouette or z-fighting.
            double[] us = {0, .4375, .5625, 1}, vs = {0, .1875, .3125, .4375, .8125, 1};
            for (var direction : net.minecraft.core.Direction.values()) {
                int normal = direction.getAxis().ordinal(), v = normal == 1 ? 2 : 1;
                int u = 3 - normal - v;
                for (int i = 0; i < us.length - 1; i++) for (int j = 0; j < vs.length - 1; j++) {
                    double[] from = new double[3], to = new double[3];
                    from[normal] = to[normal] = direction.getAxisDirection().getStep() > 0 ? 1 : 0;
                    from[u] = us[i]; to[u] = us[i+1]; from[v] = vs[j]; to[v] = vs[j+1];
                    JsonObject face = new JsonObject();
                    face.addProperty("texture", i == 1 && (j == 1 || j == 3) ? "#warning" : "#side");
                    face.addProperty("cullface", direction.getName());
                    JsonArray uv = new JsonArray();
                    for (double value : new double[]{us[i], 1-vs[j+1], us[i+1], 1-vs[j]}) uv.add(value*16);
                    face.add("uv", uv);
                    JsonObject faces = new JsonObject(); faces.add(direction.getName(), face);
                    elements.add(element(from, to, faces));
                }
            }
        }
        JsonObject model = new JsonObject(); model.add("elements", elements);
        return new ElementsModel(BlockModel.fromString(model.toString()).getElements());
    }

    private static JsonObject element(double[] min, double[] max, JsonObject faces) {
        JsonObject result = new JsonObject(); JsonArray from = new JsonArray(), to = new JsonArray();
        for (double value : min) from.add(value*16);
        for (double value : max) to.add(value*16);
        result.add("from", from); result.add("to", to); result.add("faces", faces);
        return result;
    }
}
