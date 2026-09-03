// bsi_vocab.hpp -- JSON vocabulary body -> typed bsi_vocab (contract artifact).
// Rules are BSI.md B.3 and the x-rules in bsi.schema.json; the derivations the
// host performs exactly once are listed in host/README.md.
#pragma once
#include <string>
#include <vector>
#include "../bsi_engine.h"
#include "bsi_json.hpp"

namespace bsi {

struct VocabStore {
    std::vector<bsi_material> materials;
    std::vector<bsi_section>  sections;
    std::vector<std::string>  materialNames, sectionNames, attrKeys;
    std::vector<std::string>  vendorModels, vendorJsons;      // per material ("" when none)
    std::vector<const char*>  attrKeyPtrs;
    uint32_t version = 0;
    int ignoredExtensions = 0;                                // x- keys seen in the body
    bool built = false;

    bsi_vocab view() const;
    int materialId(const std::string& name) const;
    int sectionId(const std::string& name) const;
};

struct VocabError {
    std::string code;      // VOCAB_INVALID | PROTOCOL_ERROR | UNSUPPORTED
    std::string message;
};

// Build from a schema-validated vocab.declare body. `caps` are the engine's
// declared capabilities (for x-vendor material models); pass nullptr to skip
// the capability check (probe mode).
bool buildVocab(const json::Value& body, const std::vector<std::string>* caps, VocabStore& out, VocabError& err);

// Name helpers shared with the response emitters.
const char* roleName(int role);
const char* sectKindName(int kind);

}  // namespace bsi
