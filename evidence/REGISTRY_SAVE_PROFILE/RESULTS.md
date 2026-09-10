# REGISTRY_SAVE_PROFILE — functional gates pass; timings Recorded

2026-09-10. Criteria `03696e0`, opt-in driver `e898d77`, production source `0736baa`.
This is the real Forge WorldIndexData adapter with generated metadata, invoking
save(CompoundTag), save(File), and fresh DimensionDataStorage reopen. It is not
a live game tick or whole-world/chunk save. Production I/O was unchanged for this
baseline. Measurements started only after the final RS timing JVMs completed.

Both platforms completed three fresh JVM forks: **900 total iterations / 720 measured
samples**, 150 canonical case/iteration payloads identical across all six forks.
The 144 normalized source-file hashes and the three seed files match exactly.
All original seeds, logs, exit receipts, runtime/filesystem/source identities and
samples are retained in `seeds/`, `windows/`, `linux/` and `comparison/`.

Every save cleared dirty only after success, left no temporary file, reopened with
identical coverage/object bytes, namespace, graph and epoch. READY stayed current;
PENDING64 preserved exactly the 64 destroyed positions and prior completed epoch,
then reconciled with monolith ID 1 retained. The seed files stayed unchanged.
Existing failed-write/dirty-retry and corrupt-file preservation tests remain in the
full Forge regression suite; this timing run did not inject power loss.

## Recorded cost

Same Ryzen 9 8940HX developer host as RS, fixed 2 GiB heap, allocation tracking
available. Windows 11 / NTFS / Temurin 17.0.18+8 and WSL Ubuntu 22.04 / ext4 /
OpenJDK 17.0.20+8; exact versions and paths are in identity/runtime records.
Each table entry uses 60 measured samples (20 per fork), nearest-rank p95.

| Platform | Cells | Pattern | Tag encode p95 ms | Complete file save p95 ms | Reopen p95 ms |
|---|---:|---|---:|---:|---:|
| Windows | 4096 | READY | 1.539 | 9.420 | 6.770 |
| Windows | 4096 | PENDING64 | 1.297 | 8.630 | 4.160 |
| Windows | 32768 | READY | 12.892 | 65.722 | 49.936 |
| Windows | 32768 | PENDING64 | 15.201 | 67.963 | 49.134 |
| Windows | 131072 | READY | 62.507 | 260.154 | 217.022 |
| Windows | 131072 | PENDING64 | 60.853 | 251.119 | 189.083 |
| Linux | 4096 | READY | 1.520 | 10.321 | 5.773 |
| Linux | 4096 | PENDING64 | 1.536 | 8.635 | 6.363 |
| Linux | 32768 | READY | 11.454 | 48.110 | 55.894 |
| Linux | 32768 | PENDING64 | 10.868 | 46.146 | 32.959 |
| Linux | 131072 | READY | 59.929 | 201.674 | 182.879 |
| Linux | 131072 | PENDING64 | 52.038 | 197.000 | 181.357 |

File save includes its own serialization, gzip, fsync and atomic replacement;
the three stages do not partition one save and must not be summed or subtracted
as isolated codec/CPU/I/O costs. Repeated paths/cache are warm. The ordinary
client/server remained live; hardware/process isolation was not established.
This does not measure client FPS, chunk I/O, autosave scheduling or crash durability.

At 131072 cells, compressed files are 840643–840689 bytes. Windows READY file save
allocation p95 is 10640016 B; reopen allocates 90763952 B. Linux READY is 10637648 B
and 90764272 B respectively. The complete CSV also includes p50, maximums and all
allocation/file-size observations. Timing is **Recorded only**, with no post-hoc
optimization threshold applied to this original baseline.

The ordinary jar and 519 registered tests remain the RS-qualified source; profile
drivers are opt-in and forbidden in shipping jars. CI `34414261646` at `8122715`
compiled both drivers and passed the Java/Forge, artifact and documentation gates.
Its native job skipped 15 substantive steps and cannot qualify engine packaging.
No release or engine code changed. The synchronous 131K save remains a material
main-thread cost; any optimization needs criteria frozen before its production edit.
