package com.blockreality.impl;

import com.blockreality.core.engine.GameVocabulary;
import com.blockreality.core.json.JsonValue;
import java.nio.file.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameVocabularyResourceTest {
    @Test void forgeCarriesTheSameVocabularyAndEveryRegisteredBlockHasABinding() throws Exception {
        assertEquals(Files.readString(Path.of("../mod/core/src/main/resources/blockreality/game-vocabulary.json")),GameVocabulary.declaration());
        assertEquals(9,JsonValue.parse(GameVocabulary.declaration()).arr("materials").size());
        var pattern=Pattern.compile("new StructuralBlock\\(\"([^\"]+)\", \"([^\"]+)\"");
        var matcher=pattern.matcher(Files.readString(Path.of("src/main/java/com/blockreality/impl/BRContent.java")));
        int count=0;
        while(matcher.find()) {assertNotNull(GameVocabulary.binding(matcher.group(1),matcher.group(2)));count++;}
        assertEquals(9,count,"all existing placeable structural declarations");
    }
}
