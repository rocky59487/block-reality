package com.blockreality.core.transaction;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;
import static com.blockreality.core.transaction.TransactionFixtures.*;

class TransactionCodecTest {
    @Test void phasesRoundTripCanonicallyAndOnlyCommittedReceiptsExposePieceIds() {
        var prepared = Entry.prepared(intent(request(1, 0)));
        for (var entry : List.of(prepared, prepared.finish(Phase.COMMITTED, Reason.NONE),
                prepared.finish(Phase.ABORTED, Reason.RECOVERED), Entry.rejected(request(2, 3), Reason.STALE_REVISION))) {
            byte[] bytes = TransactionCodec.encode(entry);
            assertEquals(entry, TransactionCodec.decode(bytes));
            assertArrayEquals(bytes, TransactionCodec.encode(TransactionCodec.decode(bytes)));
            assertEquals(bytes.length, ByteBuffer.wrap(bytes).getInt(8));
            if (entry.phase() == Phase.PREPARED) assertThrows(IllegalStateException.class, entry::receipt);
            else {
                assertEquals(entry.phase() == Phase.COMMITTED ? List.of(PIECE_A, PIECE_B) : List.of(), entry.receipt().created());
                assertEquals(entry.phase() == Phase.COMMITTED ? 1 : entry.request().baseRevision(), entry.receipt().revision());
            }
        }
    }

    @Test void imagesAndListsAreImmutableAndMissingIsNotEmpty() {
        byte[] bytes = {1, 2, 3}; Value image = Value.of(bytes); bytes[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, image.bytes()); image.bytes()[0] = 7;
        assertEquals(Value.of(new byte[]{1, 2, 3}), image); assertNotEquals(Value.missing(), Value.of(new byte[0]));
        assertThrows(IllegalStateException.class, () -> Value.missing().bytes());
        var intent = intent(request(1, 0));
        assertThrows(UnsupportedOperationException.class, () -> intent.changes().clear());
        assertThrows(UnsupportedOperationException.class, () -> intent.created().clear());
        assertEquals("inventory/0", intent.changes().get(0).resource());
        assertEquals(List.of(PIECE_A, PIECE_B), intent.created());
        assertEquals(image.hashCode(), Value.of(new byte[]{1, 2, 3}).hashCode());
    }

    @Test void everyCorruptedByteEveryTruncationAndTrailingDataRefuse() {
        byte[] valid = TransactionCodec.encode(Entry.prepared(intent(request(1, 0))));
        for (int offset = 0; offset < valid.length; offset++) {
            byte[] corrupt = valid.clone(); corrupt[offset] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> TransactionCodec.decode(corrupt));
            byte[] truncated = Arrays.copyOf(valid, offset);
            assertThrows(IllegalArgumentException.class, () -> TransactionCodec.decode(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> TransactionCodec.decode(Arrays.copyOf(valid, valid.length + 1)));
    }

    @Test void resignedSchemaPhaseReasonRevisionCountsAndNoncanonicalKeysRefuse() throws Exception {
        byte[] valid = TransactionCodec.encode(Entry.prepared(intent(request(1, 0))));
        for (int offset : new int[]{0, 4, 8, 116, 117, 118, 122}) {
            byte[] invalid = valid.clone();
            if (offset == 116 || offset == 117) invalid[offset] = 127;
            else ByteBuffer.wrap(invalid).putInt(offset, Integer.MAX_VALUE);
            resign(invalid); assertThrows(IllegalArgumentException.class, () -> TransactionCodec.decode(invalid), "offset " + offset);
        }
        for (long revision : new long[]{-1, Long.MAX_VALUE}) {
            byte[] invalid = valid.clone(); ByteBuffer.wrap(invalid).putLong(76, revision); resign(invalid);
            assertThrows(IllegalArgumentException.class, () -> TransactionCodec.decode(invalid));
        }
        byte[] nonAscii = valid.clone(); nonAscii[126] = (byte) 0xff; resign(nonAscii);
        assertThrows(IllegalArgumentException.class, () -> TransactionCodec.decode(nonAscii));
    }

    @Test void aggregateSizeAndIndividualResourcePieceAndBindingBoundsApplyBeforeEncoding() {
        Request r = request(1, 0); Value one = value("1"), two = value("2");
        assertThrows(IllegalArgumentException.class, () -> Value.of(new byte[MAX_VALUE_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> new Change("unicode/梁", one, two));
        assertThrows(IllegalArgumentException.class, () -> new Change("a".repeat(257), one, two));
        assertThrows(IllegalArgumentException.class, () -> new Change("a", one, one));
        var change = new Change("a", one, two);
        assertThrows(IllegalArgumentException.class, () -> new Intent(r, List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Intent(r, Collections.nCopies(MAX_RESOURCES + 1, change), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Intent(r, List.of(change, change), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Intent(r, List.of(change), List.of(PIECE_A), List.of(PIECE_A)));
        assertThrows(IllegalArgumentException.class, () -> new Intent(r, List.of(change), List.of(PIECE_A, PIECE_A), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Intent(r, List.of(change), Collections.nCopies(MAX_PIECES + 1, PIECE_A), List.of()));
        Value big = Value.of(new byte[MAX_VALUE_BYTES]); var changes = new ArrayList<Change>();
        for (int i = 0; i < 17; i++) changes.add(new Change("world/" + i, Value.missing(), big));
        assertThrows(IllegalArgumentException.class, () -> new Intent(r, changes, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Request(r.id(), ACTOR, SESSION, DOMAIN, -1, r.planHash()));
        assertThrows(IllegalArgumentException.class, () -> new Request(r.id(), ACTOR, SESSION, DOMAIN, 0, "AB".repeat(32)));
    }

    @Test void terminalPhasesCannotChangeOrSmuggleDifferentImages() {
        var p = Entry.prepared(intent(request(1, 0)));
        assertThrows(IllegalArgumentException.class, () -> new Entry(p.request(), Phase.COMMITTED, Reason.NONE, null));
        assertThrows(IllegalArgumentException.class, () -> new Entry(request(2, 0), Phase.PREPARED, Reason.NONE, p.intent()));
        assertThrows(IllegalArgumentException.class, () -> new Entry(p.request(), Phase.REJECTED, Reason.VALIDATION_REFUSED, p.intent()));
        assertThrows(IllegalArgumentException.class, () -> p.finish(Phase.COMMITTED, Reason.APPLY_FAILED));
        assertThrows(IllegalStateException.class, () -> p.finish(Phase.COMMITTED, Reason.NONE).finish(Phase.ABORTED, Reason.RECOVERED));
    }

    static void resign(byte[] bytes) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256"); hash.update(bytes, 0, bytes.length - 32);
        System.arraycopy(hash.digest(), 0, bytes, bytes.length - 32, 32);
    }
}
