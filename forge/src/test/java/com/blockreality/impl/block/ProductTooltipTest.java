package com.blockreality.impl.block;

import java.util.ArrayList;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductTooltipTest {
    @Test void millimetresRetainNonSquareOrderWithoutBinaryRoundingOrLocaleDrift() {
        var before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            var text = new ArrayList<Component>();
            ProductTooltip.append("timber", "timber_rect_140x240", text);
            var dimensions = (TranslatableContents) text.get(0).getContents();
            assertEquals("br.product.rect", dimensions.getKey());
            assertArrayEquals(new Object[]{"140", "240"}, dimensions.getArgs());
            assertEquals("br.product.member", ((TranslatableContents) text.get(1).getContents()).getKey());
        } finally { Locale.setDefault(before); }
    }

    @Test void solidCellsAndPanelsDescribeDifferentRolesWithoutInventingANormal() {
        var solid = new ArrayList<Component>();
        ProductTooltip.append("concrete", "concrete_rect_400x600", solid);
        assertEquals("br.product.solid_cell", ((TranslatableContents) solid.get(0).getContents()).getKey());
        assertEquals(0, ((TranslatableContents) solid.get(0).getContents()).getArgs().length);
        assertEquals("br.product.monolith", ((TranslatableContents) solid.get(1).getContents()).getKey());
        var panel = new ArrayList<Component>();
        ProductTooltip.append("steel", "steel_plate_20", panel);
        assertArrayEquals(new Object[]{"20"}, ((TranslatableContents) panel.get(0).getContents()).getArgs());
        assertEquals("br.product.panel", ((TranslatableContents) panel.get(1).getContents()).getKey());
    }

    @Test void unknownProductsShowInformationUnavailableInsteadOfAnEmptyOrFictionalSize() {
        var text = new ArrayList<Component>();
        ProductTooltip.append("steel", "unknown", text);
        assertEquals(1, text.size());
        assertEquals("br.product.unavailable", ((TranslatableContents) text.get(0).getContents()).getKey());
    }
}
