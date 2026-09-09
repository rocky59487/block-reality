package com.blockreality.core.world;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.engine.GameVocabulary;
import com.blockreality.core.engine.GameWorldSnapshot;
import java.util.List;

/** Player-declared product identity. Connectivity here groups objects, never solver elements. */
public record ConstructionDeclaration(String material, String section, int axis) {
    public enum Role { FRAME, MONOLITH, PANEL }
    public ConstructionDeclaration {
        GameVocabulary.binding(material, section);
        if (axis < -1 || axis > 2) throw new IllegalArgumentException("invalid declared axis");
    }
    public Role role() {
        return switch (GameVocabulary.geometry(material, section).orElseThrow(
                () -> new IllegalStateException("product declaration unavailable")).kind()) {
            case RECTANGULAR_MEMBER, CIRCULAR_MEMBER -> Role.FRAME;
            case SOLID_CELL -> Role.MONOLITH;
            case PANEL -> Role.PANEL;
        };
    }
    public ConstructionDeclaration groupDeclaration() {
        return role() == Role.FRAME ? this : new ConstructionDeclaration(material, section, -1);
    }
    public boolean sameProduct(ConstructionDeclaration other) {
        return material.equals(other.material) && section.equals(other.section);
    }
    public boolean joins(ConstructionDeclaration other) {
        return sameProduct(other) && (role() != Role.FRAME || axis >= 0 && axis == other.axis);
    }
    public List<BlockKey> neighbours(BlockKey p) {
        if (role() != Role.FRAME) return GameWorldSnapshot.neighbours(p);
        return switch (axis) {
            case 0 -> List.of(new BlockKey(p.x()-1,p.y(),p.z()), new BlockKey(p.x()+1,p.y(),p.z()));
            case 1 -> List.of(new BlockKey(p.x(),p.y()-1,p.z()), new BlockKey(p.x(),p.y()+1,p.z()));
            case 2 -> List.of(new BlockKey(p.x(),p.y(),p.z()-1), new BlockKey(p.x(),p.y(),p.z()+1));
            default -> List.of();
        };
    }
}
