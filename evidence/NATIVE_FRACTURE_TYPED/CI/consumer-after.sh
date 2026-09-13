# Use one past the current ABI; a newly supported version is not foreign.
abi=$(awk '$1=="#define" && $2=="BSI_ENGINE_ABI" {sub(/u$/, "", $3); print $3}' contract/bsi_engine.h)
test -n "$abi"
g++ -std=c++17 -shared -fPIC '-DBSI_STUB_ABI=(BSI_ENGINE_ABI+1u)' contract/host/test/stub_engine.cpp \
    -o build/host/libbsi_stub_engine_unknown.so
test -f build/host/libbsi_stub_engine_unknown.so
if build/host/bsi-hostd --engine build/host/libbsi_stub_engine_unknown.so \
    </dev/null >build/host/unknown-abi.log 2>&1; then
  echo "an unknown engine ABI was loaded; the version gate did nothing"; exit 1
fi
cat build/host/unknown-abi.log
grep -F "bsi_engine_entry returned NULL (host ABI $abi not supported)" build/host/unknown-abi.log
