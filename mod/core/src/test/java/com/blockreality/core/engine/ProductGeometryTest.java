package com.blockreality.core.engine;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductGeometryTest {
    private static final GameVocabulary.Binding STEEL = GameVocabulary.binding("steel", "steel_rect_200x400");

    @Test void catalogueExposesAllTenProductsIncludingTheUnplacedRebarDeclaration() {
        assertEquals(10, GameVocabulary.products().size());
        GameVocabulary.products().keySet().forEach(product -> assertTrue(
                GameVocabulary.geometry(product.material(), product.section()).isPresent(), product.toString()));
        assertEquals(new ProductGeometry(ProductGeometry.Kind.RECTANGULAR_MEMBER, List.of(.14, .24)),
                GameVocabulary.geometry("timber", "timber_rect_140x240").orElseThrow());
        assertEquals(new ProductGeometry(ProductGeometry.Kind.CIRCULAR_MEMBER, List.of(.025)),
                GameVocabulary.geometry("rebar", "rebar_round_d25").orElseThrow());
        assertTrue(GameVocabulary.geometry("steel", "nonexistent").isEmpty());
    }

    @Test void dimensionsComeFromTheDeclarationEvenWhenTheTokenSaysSomethingElse() {
        var changed = GameVocabulary.declaration().replace("[0.2,0.4]", "[0.37,0.19]");
        assertEquals(List.of(.37, .19), ProductGeometry.read(changed, STEEL).dimensionsMetres());
        assertEquals(List.of(.4, .2), ProductGeometry.read(GameVocabulary.declaration()
                .replace("[0.2,0.4]", "[0.4,0.2]"), STEEL).dimensionsMetres(), "width/depth order survives");
    }

    @Test void legacyMonolithSectionNamesNeverBecomeDimensionsAndPanelAliasesUseThickness() {
        for (var product : List.of(new GameVocabulary.Product("concrete", "concrete_rect_400x600"),
                new GameVocabulary.Product("brick", "brick_rect_230x350"))) {
            assertEquals(new ProductGeometry(ProductGeometry.Kind.SOLID_CELL, List.of()),
                    GameVocabulary.geometry(product.material(), product.section()).orElseThrow());
        }
        var panel = GameVocabulary.binding("concrete", "concrete_slab_200");
        assertNull(panel.section());
        assertEquals(new ProductGeometry(ProductGeometry.Kind.PANEL, List.of(.173)),
                ProductGeometry.read(GameVocabulary.declaration().replace("\"shellThickness\":0.2",
                        "\"shellThickness\":0.173"), panel));
        assertEquals(List.of(.15), GameVocabulary.geometry("concrete", "concrete_slab_150").orElseThrow().dimensionsMetres());
        assertEquals(List.of(.02), GameVocabulary.geometry("steel", "steel_plate_20").orElseThrow().dimensionsMetres());
    }

    @Test void damagedOrAmbiguousDeclarationsRefuseWithoutFallbackDimensions() {
        var declaration = GameVocabulary.declaration();
        for (String parameters : List.of("[]", "[0.2]", "[0.2,0.4,0.6]", "[0,0.4]", "[-0.2,0.4]",
                "[1e999,0.4]", "[null,0.4]", "[\"0.2\",0.4]")) {
            assertThrows(IllegalArgumentException.class, () -> ProductGeometry.read(
                    declaration.replace("[0.2,0.4]", parameters), STEEL), parameters);
        }
        for (String broken : List.of("{}", declaration + "trailing", declaration.replace("\"role\":\"member\"", "\"role\":\"guess\""),
                declaration.replace("\"kind\":\"rect\"", "\"kind\":\"i-beam\""),
                declaration.replace("\"name\":\"steel_rect_150x300\"", "\"name\":\"steel_rect_200x400\""),
                declaration.replace("\"name\":\"timber\"", "\"name\":\"steel\""),
                declaration.replace("\"name\":\"steel_rect_200x400\"", "\"name\":\"removed\""),
                declaration.replace("\"name\":\"steel\"", "\"name\":\"removed\""))) {
            assertThrows(IllegalArgumentException.class, () -> ProductGeometry.read(broken, STEEL));
        }
        var panel = GameVocabulary.binding("steel", "steel_plate_20");
        assertThrows(IllegalArgumentException.class, () -> ProductGeometry.read(
                declaration.replace("\"shellThickness\":0.02", "\"shellThickness\":0"), panel));
        assertThrows(IllegalArgumentException.class, () -> ProductGeometry.read(declaration,
                new GameVocabulary.Binding("concrete", "steel_rect_200x400")));
    }

    @Test void anExplicitBindingWinsOverTheMaterialDefaultButAbsentBindingUsesTheDeclaredDefault() {
        assertEquals(List.of(.15, .3), ProductGeometry.read(GameVocabulary.declaration(),
                GameVocabulary.binding("steel", "steel_rect_150x300")).dimensionsMetres());
        assertEquals(List.of(.2, .4), ProductGeometry.read(GameVocabulary.declaration(),
                new GameVocabulary.Binding("steel", null)).dimensionsMetres());
    }

    @Test void missingPackagedCatalogueCannotPoisonTooltipClassInitialization() throws Exception {
        // A separate class loader exercises the real resource loading and lazy presentation cache.
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{
                GameVocabulary.class.getProtectionDomain().getCodeSource().getLocation()},
                ClassLoader.getPlatformClassLoader()) {
            @Override public java.io.InputStream getResourceAsStream(String name) {
                return name.equals("blockreality/game-vocabulary.json") ? null : super.getResourceAsStream(name);
            }
        }) {
            var vocabulary = loader.loadClass(GameVocabulary.class.getName());
            var geometry = vocabulary.getMethod("geometry", String.class, String.class);
            for (int call = 0; call < 2; call++) {
                assertEquals(java.util.Optional.empty(), geometry.invoke(null, "steel", "steel_rect_200x400"));
                var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> vocabulary.getMethod("declaration").invoke(null));
                assertInstanceOf(IllegalStateException.class, failure.getCause(), "native registration still refuses explicitly");
                assertNotNull(vocabulary.getMethod("binding", String.class, String.class)
                        .invoke(null, "steel", "steel_rect_200x400"), "missing display data cannot break product identity");
            }
        }
    }
}
