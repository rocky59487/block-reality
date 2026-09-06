// Gate-only vtable observer. Link the original entry as bsi_counted_engine_entry.
// No mechanics or reply rewriting; never linked into a shipping library.
#include "../../bsi_engine.h"
#include <atomic>
#include <mutex>
#include <new>
extern "C" const bsi_engine_vtable* bsi_counted_engine_entry(uint32_t);
namespace {
std::atomic<uint64_t> counts[4]{};
std::atomic<bool> throwVocab{false};
const bsi_engine_vtable* original = nullptr;
bsi_engine_vtable counted{};
int vocab(bsi_engine* e, const bsi_vocab* v) {
    ++counts[0];
    if (throwVocab.exchange(false)) throw std::bad_alloc();
    return original->vocab(e, v);
}
int world(bsi_engine* e, const bsi_block* b, uint32_t n, const bsi_attr* a, uint32_t na, bsi_writer* w) {
    ++counts[1]; return original->world_declare(e, b, n, a, na, w);
}
int edit(bsi_engine* e, const bsi_edit* edits, uint32_t n, bsi_writer* w) {
    ++counts[2]; return original->world_edit(e, edits, n, w);
}
int solve(bsi_engine* e, const bsi_solve_options* o, const bsi_load* l, uint32_t n, bsi_writer* w) {
    ++counts[3]; return original->solve(e, o, l, n, w);
}
}
extern "C" BSI_EXPORT const bsi_engine_vtable* bsi_engine_entry(uint32_t abi) {
    static std::once_flag once;
    std::call_once(once, [] {
        original = bsi_counted_engine_entry(BSI_ENGINE_ABI);
        if (!original) return;
        counted = *original;
        counted.vocab = vocab; counted.world_declare = world;
        counted.world_edit = original->world_edit ? edit : nullptr; counted.solve = solve;
    });
    return original && abi == BSI_ENGINE_ABI ? &counted : nullptr;
}
extern "C" BSI_EXPORT uint64_t bsi_retry_test_count(uint32_t index) {
    return index < 4 ? counts[index].load() : 0;
}

extern "C" BSI_EXPORT uint32_t bsi_retry_test_supports_edit(void) { return original && original->world_edit ? 1u : 0u; }

extern "C" void bsi_retry_test_throw_vocab(void) { throwVocab = true; }
