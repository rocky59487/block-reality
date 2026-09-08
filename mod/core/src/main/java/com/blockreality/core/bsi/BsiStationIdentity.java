package com.blockreality.core.bsi;

import java.nio.ByteBuffer;
import java.util.Map;

/** Exact normalized position and source side/governing identity; always f64 on the wire. */
public record BsiStationIdentity(double s, int side, boolean governing) {
    public BsiStationIdentity {
        if (!Double.isFinite(s) || s < 0 || s > 1 || (side != -1 && side != 1)) throw invalid();
    }
    static BsiStationIdentity read(ByteBuffer b, int o) {
        int flags = Byte.toUnsignedInt(b.get(o + 9));
        if ((flags & ~1) != 0 || b.getShort(o + 10) != 0 || b.getInt(o + 12) != 0) throw invalid();
        return new BsiStationIdentity(b.getDouble(o), b.get(o + 8), (flags & 1) != 0);
    }
    static void validate(ByteBuffer b, Map<String, BsiResponse.Section> sections) {
        var ids = sections.get("stationIdentity"); if (ids == null) return;
        var members = sections.get("members"); boolean f32 = sections.containsKey("stations:f32");
        var stations = sections.get(f32 ? "stations:f32" : "stations");
        if (members == null || stations == null || ids.count() != stations.count()) throw invalid();
        int cursor = 0, previousMember = -1;
        for (int k = 0; k < members.count(); k++) {
            int mo = members.offset() + k * BsiRecords.MEMBER_BYTES;
            int id = b.getInt(mo), first = b.getInt(mo + 16), count = b.getInt(mo + 20), governing = 0;
            if (id <= previousMember || first != cursor || count < 0 || (long)first + count > ids.count()) throw invalid();
            BsiStationIdentity previous = null;
            for (int j = first; j < first + count; j++) {
                var p = read(b, ids.offset() + j * BsiRecords.STATION_IDENTITY_BYTES);
                int so = stations.offset() + j * (f32 ? BsiRecords.STATION_F32_BYTES : BsiRecords.STATION_BYTES);
                double raw = f32 ? b.getFloat(so) : b.getDouble(so);
                if (raw != (f32 ? (double)(float)p.s : p.s)) throw invalid();
                if (previous != null && (p.s < previous.s || (p.s == previous.s && (previous.side != -1 || p.side != 1)))) throw invalid();
                if (p.governing && (++governing > 1 || p.s != b.getDouble(mo + 144))) throw invalid();
                previous = p;
            }
            cursor += count; previousMember = id;
        }
        if (cursor != ids.count()) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid BSI stationIdentity"); }
}
