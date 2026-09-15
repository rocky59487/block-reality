# Evidence collection scope

Raw test, protocol, server and client attempts are copied byte for byte. Collection itself
had three distinct failures: the aggregate helper assumed an optional empty skips list;
its replacement initially used CP950 for UTF-8; and the first index-byte audit found that
an already-staged text file still held normalized newlines after adding the local
`-text` attribute. XML aggregation with `python -X utf8` and re-adding this evidence tree
with `git add --renormalize` resolve those collection failures without modifying raw data.
The succeeding audit compares every one of 1,117 staged evidence files with disk bytes.

The whitespace metadata files retain the helper's `first` filename, but were regenerated
during the seal retry. They describe the current staged evidence, not an independently
preserved earliest whitespace report. Their exit 2 records trailing whitespace in raw
logs; production/docs diff checks pass. This metadata limitation does not apply to the
retained first/second/third runtime attempts or the candidate's test/bytecode failures.

The source manifest and raw receipts are independent of collection-script portability.
No failed game or test run is counted as passing because a later aggregate succeeds.
