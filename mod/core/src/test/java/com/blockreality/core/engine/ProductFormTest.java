package com.blockreality.core.engine;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductFormTest {
    private static ProductForm rect(double width, double depth, int axis) {
        return ProductForm.of(Optional.of(new ProductGeometry(ProductGeometry.Kind.RECTANGULAR_MEMBER,
                List.of(width, depth))), axis);
    }
    private static void bounds(ProductForm form, double... expected) {
        var b = form.box();
        assertArrayEquals(expected, new double[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()}, 1e-12);
        assertTrue(form.resolved());
    }

    @Test void unequalDeclaredWidthsAndDepthsKeepTheirAxesInAllThreeOrientations() {
        bounds(rect(.2, .4, 0), 0, .3, .4, 1, .7, .6);
        bounds(rect(.2, .4, 1), .3, 0, .4, .7, 1, .6);
        bounds(rect(.2, .4, 2), .4, .3, 0, .6, .7, 1);
        bounds(rect(.15, .3, 2), .425, .35, 0, .575, .65, 1);
        bounds(rect(.1, .2, 2), .45, .4, 0, .55, .6, 1);
        bounds(rect(.14, .24, 1), .38, 0, .43, .62, 1, .57);
    }

    @Test void changedCatalogueDimensionsReachTheShapeAndBothSamplingExtents() {
        var declaration = GameVocabulary.declaration().replace("[0.2,0.4]", "[0.37,0.19]");
        var geometry = ProductGeometry.read(declaration, GameVocabulary.binding("steel", "steel_rect_200x400"));
        var form = ProductForm.of(Optional.of(geometry), 0);
        bounds(form, 0, .405, .315, 1, .595, .685);
        assertEquals(.095, form.depthHalfMetres()); assertEquals(.185, form.widthHalfMetres());
    }

    @Test void materialCellsNeverTurnLegacySectionNamesOrPanelAxesIntoPhysicalDimensions() {
        for (var product : List.of(new GameVocabulary.Product("concrete", "concrete_rect_400x600"),
                new GameVocabulary.Product("brick", "brick_rect_230x350"),
                new GameVocabulary.Product("concrete", "concrete_slab_150"),
                new GameVocabulary.Product("steel", "steel_plate_20"))) {
            for (int axis = 0; axis < 3; axis++)
                bounds(ProductForm.of(GameVocabulary.geometry(product.material(), product.section()), axis), 0,0,0,1,1,1);
        }
    }

    @Test void undeclaredMissingAndUnsupportedProductsRemainExplicitlyUnresolved() {
        assertSame(ProductForm.UNRESOLVED, rect(.2, .4, -1));
        assertSame(ProductForm.UNRESOLVED, ProductForm.of(Optional.empty(), 0));
        assertSame(ProductForm.UNRESOLVED, ProductForm.of(GameVocabulary.geometry("rebar", "rebar_round_d25"), 2));
        assertSame(ProductForm.UNRESOLVED, rect(1.1, .4, 0));
        assertSame(ProductForm.UNRESOLVED, rect(.2, 1.1, 2));
        assertFalse(ProductForm.UNRESOLVED.resolved());
        for (int axis : new int[]{-2, 3}) assertThrows(IllegalArgumentException.class, () -> rect(.2, .4, axis));
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> rect(invalid, .4, 0));
            assertThrows(IllegalArgumentException.class, () -> new ProductForm(ProductForm.CELL, .5, invalid, true));
        }
    }

    @Test void onlyFullFaceContactHidesAScanSurface() {
        var beam = rect(.2, .4, 0).box(); var thin = rect(.1, .2, 0).box();
        assertTrue(beam.coveredBy(beam, 0, 1)); assertTrue(beam.coveredBy(ProductForm.CELL, 0, -1));
        assertTrue(thin.coveredBy(beam, 0, 1));
        assertFalse(beam.coveredBy(thin, 0, 1), "partial overlap leaves visible end section");
        assertFalse(beam.coveredBy(rect(.2, .4, 1).box(), 0, 1), "neighbor does not reach the shared boundary");
        for (int sign : new int[]{-1, 1}) {
            assertFalse(beam.coveredBy(ProductForm.CELL, 1, sign), "narrow side is inside its own cell");
            assertFalse(beam.coveredBy(ProductForm.CELL, 2, sign));
            assertFalse(ProductForm.CELL.coveredBy(beam, 0, sign));
            assertTrue(ProductForm.CELL.coveredBy(ProductForm.CELL, 2, sign));
        }
    }
}
