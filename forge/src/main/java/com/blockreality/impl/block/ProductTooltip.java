package com.blockreality.impl.block;

import com.blockreality.core.engine.GameVocabulary;
import com.blockreality.core.engine.ProductGeometry;
import java.math.BigDecimal;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** Item information uses the same declarations as the native vocabulary registration. */
final class ProductTooltip {
    private ProductTooltip() { }

    static void append(String material, String section, List<Component> text) {
        var geometry = GameVocabulary.geometry(material, section);
        if (geometry.isEmpty()) {
            text.add(Component.translatable("br.product.unavailable").withStyle(ChatFormatting.GOLD));
            return;
        }
        var declared = geometry.get();
        text.add(dimensions(declared).withStyle(ChatFormatting.GRAY));
        String roleHint = switch (declared.kind()) {
            case RECTANGULAR_MEMBER, CIRCULAR_MEMBER -> "br.product.member";
            case SOLID_CELL -> "br.product.monolith";
            case PANEL -> "br.product.panel";
        };
        text.add(Component.translatable(roleHint).withStyle(ChatFormatting.DARK_GRAY));
    }

    private static net.minecraft.network.chat.MutableComponent dimensions(ProductGeometry geometry) {
        var d = geometry.dimensionsMetres();
        return switch (geometry.kind()) {
            case RECTANGULAR_MEMBER -> Component.translatable("br.product.rect", mm(d.get(0)), mm(d.get(1)));
            case CIRCULAR_MEMBER -> Component.translatable("br.product.circle", mm(d.get(0)));
            case SOLID_CELL -> Component.translatable("br.product.solid_cell");
            case PANEL -> Component.translatable("br.product.thickness", mm(d.get(0)));
        };
    }

    private static String mm(double metres) {
        return BigDecimal.valueOf(metres).movePointRight(3).stripTrailingZeros().toPlainString();
    }
}
