package com.blockreality.core.engine;

import com.blockreality.core.json.JsonValue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Declared product dimensions in metres, for presentation only; no derived section properties. */
public record ProductGeometry(Kind kind, List<Double> dimensionsMetres) {
    public enum Kind {
        RECTANGULAR_MEMBER(2), CIRCULAR_MEMBER(1), SOLID_CELL(0), PANEL(1);
        private final int dimensions;
        Kind(int dimensions) { this.dimensions = dimensions; }
    }

    public ProductGeometry {
        Objects.requireNonNull(kind);
        dimensionsMetres = List.copyOf(dimensionsMetres);
        if (dimensionsMetres.size() != kind.dimensions
                || dimensionsMetres.stream().anyMatch(d -> !Double.isFinite(d) || d <= 0))
            throw new IllegalArgumentException("invalid declared product dimensions");
    }

    static ProductGeometry read(String declaration, GameVocabulary.Binding binding) {
        var root = JsonValue.parse(declaration);
        if (!root.isObject() || !root.isArr("materials") || !root.isArr("sections"))
            throw new IllegalArgumentException("missing product vocabulary");
        var materials = named(root.arr("materials"));
        var sections = named(root.arr("sections"));
        var material = required(materials, binding.material());
        return switch (material.str("role", "")) {
            case "monolith" -> {
                requireGeneratedSection(binding);
                yield new ProductGeometry(Kind.SOLID_CELL, List.of());
            }
            case "panel" -> {
                requireGeneratedSection(binding);
                yield new ProductGeometry(Kind.PANEL,
                        List.of(material.num("shellThickness", Double.NaN)));
            }
            case "member" -> {
                // Explicit binding wins; a future product may use the declared material default.
                var section = required(sections, binding.section() == null
                        ? material.str("defaultSection", "") : binding.section());
                Kind kind = switch (section.str("kind", "")) {
                    case "rect" -> Kind.RECTANGULAR_MEMBER;
                    case "circle" -> Kind.CIRCULAR_MEMBER;
                    default -> throw new IllegalArgumentException("unknown declared section shape");
                };
                yield new ProductGeometry(kind, section.arr("p").stream()
                        .map(p -> p.asNum(Double.NaN)).toList());
            }
            default -> throw new IllegalArgumentException("unknown declared product role");
        };
    }

    private static void requireGeneratedSection(GameVocabulary.Binding binding) {
        if (binding.section() != null)
            throw new IllegalArgumentException("non-member product has an explicit frame section");
    }

    private static Map<String, JsonValue> named(List<JsonValue> values) {
        Map<String, JsonValue> result = new HashMap<>();
        for (var value : values) {
            String name = value.str("name", "");
            if (name.isBlank() || result.putIfAbsent(name, value) != null)
                throw new IllegalArgumentException("missing or duplicate product declaration name");
        }
        return result;
    }

    private static JsonValue required(Map<String, JsonValue> values, String name) {
        var value = values.get(name);
        if (value == null) throw new IllegalArgumentException("missing product declaration: " + name);
        return value;
    }
}
