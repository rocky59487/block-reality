package com.blockreality.core.bsi;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BsiVocabularyTest {
    private static final long REV = 9007199254740993L;
    private static final String REPLY = """
        {"bsi":1,"kind":"response","id":"r2","method":"bsi.vocab.declare",
         "revision":9007199254740993,"status":"ok","version":1,
         "materials":[{"id":17,"name":"steel"},{"id":3,"name":"ground_rigid"}],
         "sections":[{"id":91,"name":"steel_rect_200x400"}]}""";

    private static BsiVocabulary decode(String header) {
        byte[] frame = BsiFrame.encode(header, null);
        return BsiVocabulary.decode(BsiResponse.of(BsiFrame.decode(frame, frame.length)), "r2", REV, 1);
    }

    @Test void usesReturnedIdsInsteadOfDeclarationOrder() {
        var v = decode(REPLY);
        assertEquals(17, v.materialId("steel"));
        assertEquals(3, v.materialId("ground_rigid"));
        assertEquals(91, v.sectionId("steel_rect_200x400"));
        assertEquals(Map.of(17,"steel",3,"ground_rigid"),v.materials());
        assertThrows(IllegalArgumentException.class, () -> v.materialId("unknown"));
        assertThrows(IllegalArgumentException.class, () -> v.sectionId("unknown"));
        assertThrows(UnsupportedOperationException.class, () -> v.materials().clear());
        assertThrows(UnsupportedOperationException.class, () -> v.sections().clear());
    }

    @Test void refusesMalformedAndAmbiguousIdentityTables() {
        for (String replacement : new String[]{"-1","1.5","2147483648","null","\"17\""})
            assertThrows(IllegalArgumentException.class, () -> decode(REPLY.replace("\"id\":17", "\"id\":"+replacement)), replacement);
        for (String bad : new String[]{
                REPLY.replace("\"id\":17,", ""), REPLY.replace("\"id\":3", "\"id\":17"),
                REPLY.replace("ground_rigid", "steel"), REPLY.replace("steel\"", "\""),
                REPLY.replace("\"materials\":", "\"missing\":"), REPLY.replace("\"sections\":", "\"missing\":"),
                REPLY.replace("\"name\":\"steel\"", "\"name\":null")})
            assertThrows(IllegalArgumentException.class, () -> decode(bad), bad);
    }

    @Test void refusesRepliesForAnotherSessionRequestOrRevision() {
        for (String bad : new String[]{REPLY.replace("\"bsi\":1","\"bsi\":2"),
                REPLY.replace("response","error"), REPLY.replace("r2","r3"), REPLY.replace("declare","query"),
                REPLY.replace("9007199254740993","9007199254740992"),
                REPLY.replace("\"version\":1","\"version\":2"), REPLY.replace("\"ok\"","\"partial\"")})
            assertThrows(IllegalArgumentException.class, () -> decode(bad), bad);
    }
}
