// bsi_schema.hpp -- runtime mini JSON-Schema interpreter over the embedded
// bsi.schema.json (contract artifact). ONE source of truth: the schema file is
// embedded at build time (bsi_schema_embed.cmake) and interpreted here; there
// is no hand-transcribed validation table to drift.
//
// Supported subset (everything bsi.schema.json uses): type, const, enum,
// required, properties, additionalProperties:false, patternProperties (^x-),
// items, minItems/maxItems, uniqueItems, minimum/maximum/exclusiveMinimum,
// minLength/maxLength, pattern (two hand-coded patterns: sha256 and semver),
// oneOf, $ref to #/$defs/<name> and #/x-enums/<name>, default (reported).
#pragma once
#include <string>
#include <vector>
#include "bsi_json.hpp"

namespace bsi { namespace schema {

struct Problem {
    std::string path;      // JSON pointer-ish: body.precision.tier
    std::string what;      // human text (diagnostic only; never a contract token)
    bool unknownKey = false;   // an unknown non-x- key (=> PROTOCOL_ERROR)
};

struct Result {
    bool ok = true;
    std::vector<Problem> problems;
    int ignoredExtensions = 0;   // number of x- keys encountered (P6)
};

class Schema {
public:
    // Parse the embedded (or any) schema text. Returns false if it is not JSON.
    bool load(const char* text, size_t n);
    const json::Value& root() const { return root_; }

    // Validate `v` against "#/$defs/<def>".
    Result validate(const std::string& def, const json::Value& v) const;
    // Validate against an arbitrary schema node.
    Result validateNode(const json::Value& node, const json::Value& v, const std::string& path) const;

    // Property order of "#/$defs/<def>" (BSI B.5: response headers emit keys in
    // schema order). Empty if the def has no properties.
    std::vector<std::string> propertyOrder(const std::string& def) const;
    // Reorder the top-level keys of an object to schema order; keys absent from
    // the schema keep their relative order after the known ones.
    void orderBySchema(const std::string& def, json::Value& obj) const;

    // Enumerations (#/x-enums/<name>) as string lists.
    std::vector<std::string> enumValues(const std::string& name) const;
    // Index of a token in an enumeration, -1 if absent.
    int enumIndex(const std::string& name, const std::string& token) const;

    // x-records: record byte size by section name ("stations:f32" resolves the f32 variant).
    long recordBytes(const std::string& section) const;
    // x-capabilities / x-errors / x-verbs membership.
    bool isCapability(const std::string& s) const { return inList("x-capabilities", s); }
    bool isError(const std::string& s) const { return inList("x-errors", s); }
    bool isVerb(const std::string& s) const { return inList("x-verbs", s); }

private:
    json::Value root_;
    const json::Value* resolveRef(const std::string& ref) const;
    bool inList(const char* key, const std::string& s) const;
    void checkNode(const json::Value& node, const json::Value& v, const std::string& path, Result& r) const;
    static bool typeMatches(const std::string& type, const json::Value& v);
    static bool valueEquals(const json::Value& a, const json::Value& b);
    static bool patternMatches(const std::string& pattern, const std::string& s);
};

// The embedded contract schema and hash (defined by the generated bsi_schema_blob.cpp).
extern const char* const kEmbeddedSchemaText;
extern const unsigned long kEmbeddedSchemaBytes;
extern const char* const kEmbeddedContractSha256;

// Process-wide parsed schema (lazy, thread-safe init).
const Schema& embedded();

}}  // namespace bsi::schema
