package com.blockreality.testfixtures;

import java.util.Arrays;

/** A removed legacy field is stronger than an always-empty Optional in the shipping API. */
public final class NativeSnapshotChecks {
    private NativeSnapshotChecks() { }

    public static boolean hasNoLegacyField(Object snapshot) {
        return snapshot.getClass().isRecord() && Arrays.stream(snapshot.getClass().getRecordComponents())
                .noneMatch(component -> component.getName().equals("field")
                        || component.getGenericType().getTypeName().contains("testlegacy")
                        || component.getGenericType().getTypeName().contains("FieldSpec"));
    }
}
