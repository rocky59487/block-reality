# ABI2 is supported; compile the same stub with an explicitly unknown ABI3.
g++ -std=c++17 -shared -fPIC -DBSI_STUB_ABI=3u contract/host/test/stub_engine.cpp \
    -o build/host/libbsi_stub_engine_unknown.so
test -f build/host/libbsi_stub_engine_unknown.so
if build/host/bsi-hostd --engine build/host/libbsi_stub_engine_unknown.so \
    </dev/null >build/host/unknown-abi.log 2>&1; then
  echo "an unknown engine ABI was loaded; the version gate did nothing"; exit 1
fi
cat build/host/unknown-abi.log
grep -F 'bsi_engine_entry returned NULL (host ABI 2 not supported)' build/host/unknown-abi.log
