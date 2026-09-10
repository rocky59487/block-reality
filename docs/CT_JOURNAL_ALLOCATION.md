# CT journal validation allocation — frozen before optimization

2026-09-10. Baseline shipping source ee491960828d3734efbb4da241c15487815ea1ed.
This is a bounded implementation refinement within the still-open CT unit, not a
replacement for CT-1..9 or v1 performance qualification.

The first fixed journal-only fixture (1/128/4096 cells,8 warmups and40 samples each,
one JVM per platform) recorded4096-cell median allocated bytes of9,030,408 Windows
and9,025,736 Linux. The respective create+decide p95 values were12.547 ms and125.573 ms.
The fixture excludes Intent construction, Minecraft participant writes, native work and
UI. Those numbers are Recorded, not a live transaction/FPS acceptance result. Raw first
samples must remain available even if a later run is faster.

Planned change: cache immutable compiled request/resource validation patterns instead
of compiling them for each decoded participant. No accepted character, count, image,
record layout, checksum, ownership or fsync/atomic-replacement rule may change.

Required before adopting this refinement:

1. Run exactly the same TransactionJournalCost source and fixture on both platforms.
   In the timed create+decide interval,4096-cell median allocated bytes must be at most
   60% of the respective first baseline. Do not move allocation outside that interval.
2. All144 terminal journal record files per platform must be byte-identical to that
   platform's baseline, including the8 warmups in each of the3 cases. Also compare the
   two platforms' final144 records directly.
3. The27 core transaction tests, real37 crash/recovery cases and concurrent JVM owner
   refusal must still pass. Re-run full core/Forge suites with the delivered libraries
   and retain packaging/source/bytecode checks before presenting a new candidate.
4. Record all timing/allocation samples and the first result of every run. Timing is
   Recorded only: there is no performance acceptance threshold or v1 FPS claim here.
   A memory reduction cannot excuse any weakened durability or behavioral assertion.

The broader Forge adapter and full construction transaction gates remain required.
