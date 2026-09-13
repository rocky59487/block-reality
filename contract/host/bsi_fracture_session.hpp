// Included in SessionImpl's private section; all transports use these verbs.
    bool identified_ = false, nativeFault_ = false;
    bsi_world_stamp worldStamp_{};
    bsi_id128 artifactNamespace_{};
    std::vector<bsi_artifact_owner> owners_;
    std::unique_ptr<fracture_wire::Candidate> fracture_;

    void forgetIdentity() { identified_ = false; owners_.clear(); fracture_.reset(); }
    void wireError(const Request& rq, Reply& out, const char* code, const std::string& why) {
        errorReply(out, rq.id, rq.method, rq.revision, code, why);
    }
    bool fractureCapability(const Request& rq, Reply& out, bool fracture) {
#ifndef BSI_TEST_NFW_CAP
        if (!has("bsi.world.identity") || (fracture && !has("bsi.fracture"))) {
            wireError(rq, out, "UNSUPPORTED", "engine lacks identified world or fracture capability"); return false;
        }
#endif
        // Never read appended slots through an older ABI object.
        if (
#ifndef BSI_TEST_NFW_ABI
            engine_.vt->abi_version < 3 ||
#endif
            !engine_.vt->world_declare_identified || !engine_.vt->world_edit_identified ||
            (fracture && (!engine_.vt->fracture_prepare || !engine_.vt->fracture_finish))) {
            wireError(rq, out, "UNSUPPORTED", "engine lacks ABI3 identified world or fracture entry points"); return false;
        }
        return true;
    }
    bool fractureBody(const Request& rq, Reply& out, const char* def, size_t n, bool emptyPayload) {
        if (!rq.body || !fracture_wire::uniqueKeys(rq.hdr) || n > fracture_wire::kPayloadLimit || (emptyPayload && n)) {
            wireError(rq, out, "PROTOCOL_ERROR", "invalid fracture body, duplicate key or payload length"); return false;
        }
        auto result = schema::embedded().validate(def, *rq.body);
        if (!result.ok) { wireError(rq, out, "PROTOCOL_ERROR", firstProblem(result)); return false; }
        return true;
    }
    bool identifiedBlock(const Request& rq, Reply& out, const bsi_block& b) {
        auto verdict = canon::checkBlock(b, uint32_t(vocab_.materials.size()), uint32_t(vocab_.sections.size()));
        if (!verdict.ok) { wireError(rq, out, verdict.code.c_str(), verdict.message); return false; }
        if (b.attr) { wireError(rq, out, "UNSUPPORTED", "identified world does not support attributes"); return false; }
        return true;
    }
    bool wireHeader(const Request& rq, Reply& out, json::Writer& jw) {
        out.header = jw.take();
        if (out.header.size() <= fracture_wire::kHeaderLimit) return true;
        wireError(rq, out, "INTERNAL", "fracture reply exceeds header budget; reopen session"); return false;
    }
    void identifiedWorld(const Request& rq, const uint8_t* payload, size_t n, Reply& out, bool edit) {
        if (!fractureCapability(rq, out, false)) return;
        if (!vocabDeclared_) { wireError(rq, out, "VOCAB_INVALID", "no vocabulary declared"); return; }
        if (edit && (!worldDeclared_ || !identified_)) { wireError(rq, out, "NO_WORLD", "no identified world"); return; }
        if (!fractureBody(rq, out, edit ? "world.edit.body" : "world.declare.body", n, false)) return;
        const auto& body = *rq.body;
        const auto& identity = *body.find("identity");
        const bsi_world_stamp expected = fracture_wire::stamp(identity);
        const bsi_id128 ns = edit ? artifactNamespace_ : fracture_wire::id(*identity.find("artifactNamespace"));
        if (!fracture_wire::nonzero(expected.domain) || !fracture_wire::nonzero(ns) || (edit && expected.revision == INT64_MAX)) {
            wireError(rq, out, "PROTOCOL_ERROR", "zero identity or exhausted revision"); return;
        }
        if (!edit && (!body.find("attrs") || body.find("attrs")->i64 != 0)) {
            wireError(rq, out, "PROTOCOL_ERROR", "identified declare requires attrs=0"); return;
        }
        const uint64_t count = uint64_t(body.find(edit ? "edits" : "blocks")->i64);
        const uint64_t ownerCount = uint64_t(identity.find("owners")->i64);
        const uint64_t recordsBytes = count * (edit ? sizeof(bsi_edit) : sizeof(bsi_block));
        if (!count || recordsBytes + ownerCount * sizeof(bsi_artifact_owner) != n || !payload) {
            wireError(rq, out, "PROTOCOL_ERROR", "identified payload length mismatch"); return;
        }
        std::vector<bsi_block> blocks;
        std::vector<bsi_edit> edits;
        if (edit) {
            edits.resize(size_t(count)); std::memcpy(edits.data(), payload, size_t(recordsBytes));
            for (const auto& e : edits) {
                if (e.op > 2) { wireError(rq, out, "PROTOCOL_ERROR", "invalid edit op"); return; }
                if (!identifiedBlock(rq, out, e.block)) return;
            }
            blocks = fracture_wire::editedWorld(world_, edits);
        } else {
            blocks.resize(size_t(count)); std::memcpy(blocks.data(), payload, size_t(recordsBytes));
            for (const auto& b : blocks) if (!identifiedBlock(rq, out, b)) return;
            auto canonical = canon::canonicalise(blocks);
            if (!canonical.ok) { wireError(rq, out, "PROTOCOL_ERROR", canonical.message); return; }
        }
        std::vector<bsi_artifact_owner> owners((size_t)ownerCount);
        if (ownerCount) std::memcpy(owners.data(), payload + recordsBytes, size_t(ownerCount) * sizeof(bsi_artifact_owner));
        if (!fracture_wire::ownersMatch(owners, blocks, vocab_.materials)) {
            wireError(rq, out, "PROTOCOL_ERROR", "owners must cover every structural source exactly once"); return;
        }
        ReplyBuilder builder(uint32_t(blocks.size()), 0, BSI_STORAGE_F64); bsi_writer writer{&builder};
        bsi_world_stamp after = expected;
        // Any exception or malformed success after dispatch invalidates this session.
        nativeFault_ = true;
        int status;
        if (edit) {
            bsi_identified_edit input{}; input.struct_size = sizeof input; input.expected = expected;
            input.owners = owners.data(); input.nOwners = uint32_t(owners.size());
            status = engine_.vt->world_edit_identified(inst_, edits.data(), uint32_t(edits.size()), &input, &writer);
            ++after.revision;
        } else {
            bsi_world_identity input{}; input.struct_size = sizeof input; input.stamp = expected; input.artifactNamespace = ns;
            input.owners = owners.data(); input.nOwners = uint32_t(owners.size());
            status = engine_.vt->world_declare_identified(inst_, blocks.data(), uint32_t(blocks.size()), &input, &writer);
        }
        if (status != BSI_OK) { nativeFault_ = false; errorFromBuilder(out, rq, status, builder); return; }
        std::string why;
        if (!builder.finalizeDeclare(why) || (edit && (!builder.haveEdit() || !fracture_wire::same(expected, worldStamp_)))) {
            wireError(rq, out, "INTERNAL", why.empty() ? "invalid identified edit success; reopen session" : why); return;
        }
        auto validation = schema::embedded().validate(edit ? "world.edit.body" : "world.declare.body", body);
        const int extensions = rq.ignoredExt + validation.ignoredExtensions;
        json::Writer jw; beginResponse(jw, rq, "response"); jw.kv("status", "ok");
        writeDiag(jw, builder, uint32_t(blocks.size()), vocab_.ignoredExtensions + (edit ? worldExt_ : 0) + extensions);
        if (edit) {
            jw.key("edit"); jw.beginObj(); jw.kv("class", std::string(1, builder.editCls()));
            if (!builder.editDowngraded().empty()) jw.kv("downgraded", builder.editDowngraded());
            jw.endObj();
        }
        jw.key("identity"); fracture_wire::writeIdentity(jw, after, ns); jw.endObj();
        if (!wireHeader(rq, out, jw)) return;
        world_ = std::move(blocks); owners_ = std::move(owners); attrs_.clear(); fracture_.reset();
        worldDeclared_ = identified_ = true; worldStamp_ = after; artifactNamespace_ = ns;
        if (!edit) worldExt_ = extensions;
        nativeFault_ = false;
    }
    void fracturePrepare(const Request& rq, size_t n, Reply& out) {
        if (!fractureCapability(rq, out, true) || !fractureBody(rq, out, "fracture.prepare.body", n, true)) return;
        if (!identified_) { wireError(rq, out, "NO_WORLD", "no identified world"); return; }
        const auto& body = *rq.body;
        bsi_fracture_options options{}; options.struct_size = sizeof options;
        options.expected = fracture_wire::stamp(*body.find("expected")); options.request = fracture_wire::id(*body.find("requestId"));
        options.budget = uint32_t(body.find("budget")->i64); options.tier = BSI_TIER_COMMIT; options.numThreads = opts_.numThreads;
        if (const auto* threads = body.find("numThreads")) options.numThreads = uint32_t(threads->i64);
        for (int k = 0; k < 3; ++k) options.gravity[k] = body.find("gravity")->arr[k].num;
        if (!fracture_wire::nonzero(options.expected.domain) || !fracture_wire::nonzero(options.request) || options.expected.revision == INT64_MAX) {
            wireError(rq, out, "PROTOCOL_ERROR", "zero identity or exhausted revision"); return;
        }
        std::unique_ptr<fracture_wire::Candidate> next(new fracture_wire::Candidate);
        ReplyBuilder builder(uint32_t(world_.size()), 0, BSI_STORAGE_F64); bsi_writer writer{&builder};
        const bsi_fracture_view* view = nullptr;
        nativeFault_ = true;
        int status = engine_.vt->fracture_prepare(inst_, &options, &view, &writer);
        if (status != BSI_OK) { nativeFault_ = false; errorFromBuilder(out, rq, status, builder); return; }
        std::vector<SectionInfo> sections; std::string why;
        if (builder.hasError() || !fracture_wire::same(options.expected, worldStamp_) ||
            !fracture_wire::receipt(view, options, artifactNamespace_, world_, owners_, vocab_.materials, *next, sections, out.payload, why)) {
            wireError(rq, out, "INTERNAL", why.empty() ? "invalid fracture success; reopen session" : why); return;
        }
        json::Writer jw; beginResponse(jw, rq, "response"); jw.kv("status", "prepared");
        jw.kv("requestId", fracture_wire::hex(view->request));
        jw.key("before"); fracture_wire::writeStamp(jw, view->before);
        jw.key("after"); fracture_wire::writeStamp(jw, view->after);
        jw.kv("artifactNamespace", fracture_wire::hex(view->artifactNamespace));
        jw.key("token"); fracture_wire::writeToken(jw, view->token);
        jw.kv("steps", (unsigned long long)view->steps); jw.kv("flags", (int)view->flags);
        jw.kv("remainingBlocks", (unsigned long long)next->remaining.size());
        jw.key("sections"); jw.beginArr();
        for (const auto& s : sections) {
            jw.beginObj(); jw.kv("name", s.name); jw.kv("offset", (unsigned long long)s.offset);
            jw.kv("bytes", (unsigned long long)s.bytes); jw.kv("count", (unsigned long long)s.count); jw.endObj();
        }
        jw.endArr(); jw.endObj();
        if (!wireHeader(rq, out, jw)) return;
        fracture_ = std::move(next); nativeFault_ = false;
    }
    void fractureFinish(const Request& rq, size_t n, Reply& out) {
        if (!fractureCapability(rq, out, true) || !fractureBody(rq, out, "fracture.finish.body", n, true)) return;
        if (!identified_) { wireError(rq, out, "NO_WORLD", "no identified world"); return; }
        const auto& body = *rq.body;
        bsi_fracture_finish input{}; input.struct_size = sizeof input;
        input.expected = fracture_wire::stamp(*body.find("expected")); input.request = fracture_wire::id(*body.find("requestId"));
        input.token = fracture_wire::token(*body.find("token")); input.resultRevision = body.find("resultRevision")->i64;
        input.action = body.find("action")->str == "commit" ? 1 : 0;
        if (!fracture_wire::nonzero(input.expected.domain) || !fracture_wire::nonzero(input.request) ||
            !fracture_wire::nonzero(input.token.context) || !input.token.sequence) {
            wireError(rq, out, "PROTOCOL_ERROR", "zero fracture identity or token"); return;
        }
        // Build every possible success response before the native world commit.
        std::array<std::string, 3> headers;
        const char* names[] = {"committed", "replayed", "discarded"};
        for (size_t k = 0; k < headers.size(); ++k) {
            json::Writer jw; beginResponse(jw, rq, "response"); jw.kv("status", names[k]);
            jw.kv("requestId", fracture_wire::hex(input.request)); jw.key("token"); fracture_wire::writeToken(jw, input.token);
            jw.key("identity"); fracture_wire::writeIdentity(jw, (k == 0 && fracture_) ? fracture_->after : worldStamp_, artifactNamespace_);
            jw.endObj(); headers[k] = jw.take();
            if (headers[k].size() > fracture_wire::kHeaderLimit) { wireError(rq, out, "PROTOCOL_ERROR", "fracture reply exceeds header budget"); return; }
        }
        ReplyBuilder builder(uint32_t(world_.size()), 0, BSI_STORAGE_F64); bsi_writer writer{&builder}; uint8_t result = 255;
        nativeFault_ = true;
        int status = engine_.vt->fracture_finish(inst_, &input, &result, &writer);
        if (status != BSI_OK) { nativeFault_ = false; errorFromBuilder(out, rq, status, builder); return; }
        if (builder.hasError() || !fracture_ || result > BSI_FRACTURE_DISCARDED ||
            !fracture_wire::same(input.token, fracture_->token) || !fracture_wire::same(input.request, fracture_->request) ||
            !fracture_wire::same(input.expected, fracture_->before) || input.resultRevision != fracture_->after.revision ||
            (!input.action ? result != BSI_FRACTURE_DISCARDED : result != (fracture_->committed ? BSI_FRACTURE_REPLAYED : BSI_FRACTURE_COMMITTED))) {
            wireError(rq, out, "INTERNAL", "invalid fracture finish success; reopen session"); return;
        }
        if (result == BSI_FRACTURE_COMMITTED) {
#ifndef BSI_TEST_NFW_CACHE
            world_ = std::move(fracture_->remaining); owners_ = std::move(fracture_->owners);
#endif
            worldStamp_ = fracture_->after; fracture_->committed = true;
        } else if (result == BSI_FRACTURE_DISCARDED) fracture_.reset();
        out.header = std::move(headers[result]); nativeFault_ = false;
    }
