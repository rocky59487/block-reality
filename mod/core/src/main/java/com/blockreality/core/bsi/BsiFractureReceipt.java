package com.blockreality.core.bsi;

import com.blockreality.api.geom.BlockKey;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import static com.blockreality.core.bsi.BsiFracture.*;

/** Immutable native source, partition and physical properties; no reconstructed physics. */
public final class BsiFractureReceipt {
    private static final List<String> NAMES = List.of("physicalTotals","fractureCells","fractureFragments",
            "fractureParents","fractureEvents","fractureEventCells","fractureMechanism");
    private static final int[] STRIDES = {80,144,96,8,24,4,12};
    public record Physical(double mass, double cx, double cy, double cz,
                           double ixx, double iyy, double izz, double ixy, double ixz, double iyz) { }
    public record Cell(BsiRecords.Block source, int panelNormal, int group, long artifact, boolean unrepresented, Physical physical) { }
    public record Fragment(Physical physical, List<Long> parents, int flags) {
        public Fragment { parents = List.copyOf(parents); }
        /** Mechanical features require a suitable dynamic model; connectivity is not rigidity. */
        public boolean hasReleases() { return (flags & 1) != 0; }
        public boolean hasTensionOnly() { return (flags & 2) != 0; }
        public boolean hasCouplings() { return (flags & 4) != 0; }
    }
    public record Event(List<Integer> cells, double utilization, int capacityFace) {
        public Event { cells = List.copyOf(cells); }
    }
    private final UUID request, artifactNamespace;
    private final Stamp before, after;
    private final Token token;
    private final int steps, flags, remainingBlocks;
    private final List<Cell> cells;
    private final List<Fragment> fragments;
    private final List<Event> events;
    private final List<BlockKey> mechanism;
    private final List<Physical> totals;
    private final BsiResponse response;
    private final World source;

    private BsiFractureReceipt(BsiResponse response, World world, UUID request, Options options, String id) {
        checkEnvelope(response,id,"bsi.fracture.prepare",world.stamp().revision());
        var h = response.header();
        if (!response.status().equals("prepared") || !uuid(h.str("requestId","")).equals(request)) throw invalid("prepare request binding");
        this.request = request; this.artifactNamespace = uuid(h.str("artifactNamespace",""));
        before = readStamp(h.objField("before")); after = readStamp(h.objField("after")); token = readToken(h.objField("token"));
        if (!before.equals(world.stamp()) || !artifactNamespace.equals(world.artifactNamespace()) || before.revision() == Long.MAX_VALUE
                || !after.equals(new Stamp(before.domain(),before.revision()+1))) throw invalid("prepare world identity");
        steps = integer(h.exactI64("steps")); flags = integer(h.exactI64("flags")); remainingBlocks = integer(h.exactI64("remainingBlocks"));
        if (steps > options.budget() || flags > 3) throw invalid("prepare diagnostic range");
        byte[] payload = response.payload();
        if (payload.length > MAX_PAYLOAD || !new ArrayList<>(response.sections().keySet()).equals(NAMES)) throw invalid("fracture directory");
        int offset = 0;
        for (int i=0;i<NAMES.size();i++) {
            var section = response.sections().get(NAMES.get(i));
            if (section.offset() != offset || (long)section.count()*STRIDES[i] != section.bytes()) throw invalid("fracture stride or gap");
            offset = Math.addExact(offset,section.bytes());
        }
        if (offset != payload.length || response.sections().get("physicalTotals").count() != 3
                || response.sections().get("fractureCells").count() != world.owners().size()) throw invalid("fracture coverage count");
        var b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        totals = List.of(physical(b,0),physical(b,80),physical(b,160));
        int fragmentCount = response.sections().get("fractureFragments").count();
        if (fragmentCount > world.owners().size()) throw invalid("fragment count");
        var expectedBlocks = new HashMap<BlockKey,BsiRecords.Block>();
        for (var block : world.blocks()) expectedBlocks.put(key(block),block);
        var parsedCells = new ArrayList<Cell>(world.owners().size());
        var parentSets = new ArrayList<SortedSet<Long>>(fragmentCount);
        var firstCells = new ArrayList<BlockKey>(Collections.nCopies(fragmentCount,null));
        for (int i=0;i<fragmentCount;i++) parentSets.add(new TreeSet<>());
        int remaining = world.blocks().size();
        for (int i=0;i<world.owners().size();i++) {
            int at = 240+i*144; var owner = world.owners().get(i); var expected = expectedBlocks.get(owner.position());
            var expectedBytes = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN); expected.write(expectedBytes);
            for (int k=0;k<40;k++) if (b.get(at+k) != expectedBytes.get(k)) throw invalid("native source changed");
            int normal=b.getInt(at+40), group=b.getInt(at+44); long artifact=b.getLong(at+48); int cellFlags=b.getInt(at+56);
            if (normal < -1 || normal > 2 || group < 0 || (long)group > fragmentCount+1L || artifact != owner.artifact()
                    || cellFlags < 0 || cellFlags > 1 || cellFlags != 0 && group != 0 || b.getInt(at+60) != 0) throw invalid("cell partition or ownership");
            var cell = new Cell(expected,normal,group,artifact,cellFlags != 0,physical(b,at+64)); parsedCells.add(cell);
            if (group != 0) remaining--;
            if (group >= 2) { parentSets.get(group-2).add(artifact); if (firstCells.get(group-2) == null) firstCells.set(group-2,owner.position()); }
        }
        if (remaining != remainingBlocks) throw invalid("remaining world count");
        cells = List.copyOf(parsedCells);
        var parsedFragments = new ArrayList<Fragment>(fragmentCount); int parentOffset=0;
        var fragmentSection=response.sections().get("fractureFragments"); var parentSection=response.sections().get("fractureParents");
        BlockKey previous = null;
        for (int i=0;i<fragmentCount;i++) {
            int at=fragmentSection.offset()+i*96; var expected=parentSets.get(i); var first=firstCells.get(i);
            int start=integer(b.getInt(at+80)), count=integer(b.getInt(at+84)), fragmentFlags=integer(b.getInt(at+88));
            if (first == null || previous != null && ORDER.compare(previous,first) >= 0 || expected.size() != count
                    || start != parentOffset || (long)start+count > parentSection.count() || fragmentFlags > 7 || b.getInt(at+92) != 0) throw invalid("fragment parent range or order");
            previous=first;
            for (long parent : expected) if (b.getLong(parentSection.offset()+8*parentOffset++) != parent) throw invalid("fragment parents changed");
            parsedFragments.add(new Fragment(physical(b,at),List.copyOf(expected),fragmentFlags));
        }
        if (parentOffset != parentSection.count()) throw invalid("unclaimed parents");
        fragments=List.copyOf(parsedFragments);
        var parsedEvents=new ArrayList<Event>(); var eventSection=response.sections().get("fractureEvents");
        var eventCells=response.sections().get("fractureEventCells"); int eventOffset=0; var covered=new BitSet(cells.size());
        for (int i=0;i<eventSection.count();i++) {
            int at=eventSection.offset()+i*24, start=integer(b.getInt(at)), count=integer(b.getInt(at+4));
            double utilization=b.getDouble(at+8); int face=Byte.toUnsignedInt(b.get(at+16));
            if (start != eventOffset || count == 0 || (long)start+count > eventCells.count() || !Double.isFinite(utilization) || utilization < 0 || face > 5) throw invalid("event range or capacity face");
            for (int k=17;k<24;k++) if (b.get(at+k) != 0) throw invalid("event reserved bytes");
            var indices=new ArrayList<Integer>(count);
            for (int k=0;k<count;k++) {
                int cell=integer(b.getInt(eventCells.offset()+4*eventOffset++));
                if (cell >= cells.size() || covered.get(cell) || cells.get(cell).group() != 1) throw invalid("event cell coverage");
                covered.set(cell); indices.add(cell);
            }
            parsedEvents.add(new Event(indices,utilization,face));
        }
        if (eventOffset != eventCells.count()) throw invalid("unclaimed event cells");
        for (int i=0;i<cells.size();i++) if ((cells.get(i).group() == 1) != covered.get(i)) throw invalid("broken source without event");
        events=List.copyOf(parsedEvents);
        var ownerPositions=new HashSet<BlockKey>(); for (var owner:world.owners()) ownerPositions.add(owner.position());
        var parsedMechanism=new ArrayList<BlockKey>(); var mechanismSection=response.sections().get("fractureMechanism");
        if (mechanismSection.count() > cells.size()) throw invalid("mechanism count");
        for (int i=0;i<mechanismSection.count();i++) {
            int at=mechanismSection.offset()+i*12; var pos=new BlockKey(b.getInt(at),b.getInt(at+4),b.getInt(at+8));
            if (!ownerPositions.contains(pos)) throw invalid("foreign mechanism source");
            parsedMechanism.add(pos);
        }
        mechanism=List.copyOf(parsedMechanism); this.response=response; source=world;
    }
    public static BsiFractureReceipt decode(BsiResponse response, World world, UUID request, Options options, String id) {
        return new BsiFractureReceipt(response,world,request,options,id);
    }
    private static Physical physical(ByteBuffer b,int at) {
        for (int k=0;k<10;k++) if (!Double.isFinite(b.getDouble(at+8*k))) throw invalid("nonfinite physical record");
        if (b.getDouble(at) < 0 || b.getDouble(at+32) < 0 || b.getDouble(at+40) < 0 || b.getDouble(at+48) < 0) throw invalid("negative physical record");
        return new Physical(b.getDouble(at),b.getDouble(at+8),b.getDouble(at+16),b.getDouble(at+24),b.getDouble(at+32),b.getDouble(at+40),b.getDouble(at+48),b.getDouble(at+56),b.getDouble(at+64),b.getDouble(at+72));
    }
    private static int integer(long value) { if (value < 0 || value > Integer.MAX_VALUE) throw invalid("integer range"); return (int)value; }
    public UUID request() { return request; }
    public UUID artifactNamespace() { return artifactNamespace; }
    public Stamp before() { return before; }
    public Stamp after() { return after; }
    public Token token() { return token; }
    boolean matchesSource(World world) { return source.equals(world); }
    public int steps() { return steps; }
    public int flags() { return flags; }
    public int remainingBlocks() { return remainingBlocks; }
    public List<Cell> cells() { return cells; }
    public List<Fragment> fragments() { return fragments; }
    public List<Event> events() { return events; }
    public List<BlockKey> mechanism() { return mechanism; }
    public List<Physical> totals() { return totals; }
    /** Historical evidence only: a stored token must never be used after a session restart. */
    public byte[] frame() {
        byte[] bytes=BsiFrame.encode(response.headerText(),response.payload());
        bytes[2] |= BsiFrame.FLAG_END_OF_RESPONSE; return bytes;
    }
}
