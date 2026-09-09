package com.blockreality.core.engine;

import com.blockreality.core.bsi.BsiVocabulary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Product declarations and SI material data; section properties remain engine-owned. */
public final class GameVocabulary {
    private GameVocabulary() { }

    public record Product(String material, String section) { }
    public record Binding(String material, String section) {
        public int materialId(BsiVocabulary vocabulary) { return vocabulary.materialId(material); }
        /** null means this product deliberately delegates section generation/defaults to the engine. */
        public int sectionId(BsiVocabulary vocabulary) { return section == null ? -1 : vocabulary.sectionId(section); }
    }

    private static final Map<Product, Binding> PRODUCTS = Map.ofEntries(
            member("steel", "steel_rect_200x400"), member("steel", "steel_rect_150x300"),
            member("steel", "steel_rect_100x200"), member("timber", "timber_rect_140x240"),
            member("rebar", "rebar_round_d25"),
            Map.entry(new Product("concrete", "concrete_rect_400x600"), new Binding("concrete", null)),
            Map.entry(new Product("brick", "brick_rect_230x350"), new Binding("brick", null)),
            panel("concrete", "concrete_slab_200"), panel("concrete", "concrete_slab_150"),
            panel("steel", "steel_plate_20"));

    private static Map.Entry<Product, Binding> member(String material, String section) {
        return Map.entry(new Product(material, section), new Binding(material, section));
    }
    private static Map.Entry<Product, Binding> panel(String material, String section) {
        return Map.entry(new Product(material, section), new Binding(section, null));
    }
    public static Map<Product, Binding> products() { return PRODUCTS; }
    public static Binding binding(String material, String section) {
        var binding = PRODUCTS.get(new Product(material, section));
        if (binding == null) throw new IllegalArgumentException("unknown structural declaration: " + material + "/" + section);
        return binding;
    }

    public static String declaration() { return Declaration.JSON; }
    private static final class Declaration {
        private static final String JSON = read();
        private static String read() {
            try (var in = GameVocabulary.class.getResourceAsStream("/blockreality/game-vocabulary.json")) {
                if (in == null) throw new IllegalStateException("missing game vocabulary resource");
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) { throw new IllegalStateException("cannot read game vocabulary", e); }
        }
    }
}
