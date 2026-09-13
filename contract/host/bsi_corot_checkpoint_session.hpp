// Included in SessionImpl. Opaque checkpoint bytes belong to the engine.
    void corotCheckpointRequest(const Request& rq,const uint8_t* payload,size_t n,Reply& out) {
        const bool importing=rq.method=="bsi.corot.checkpoint.import";
        if(!has("bsi.corot.checkpoint")||engine_.vt->abi_version<8||!engine_.vt->corot_checkpoint_export||!engine_.vt->corot_checkpoint_import){
            wireError(rq,out,"UNSUPPORTED","engine lacks portable finite checkpoints");return;}
        if(!fractureBody(rq,out,importing?"corot.checkpoint.import.body":"corot.checkpoint.export.body",n,!importing))return;
        if(!identified_){wireError(rq,out,"NO_WORLD","no identified world");return;}
        const auto& body=*rq.body;bsi_corot_checkpoint_options options{};options.struct_size=sizeof(options);options.format=uint32_t(body.find("format")->num);
        options.expected=fracture_wire::stamp(*body.find("expected"));options.byteBudget=uint32_t(body.find("byteBudget")->num);
        if(options.format==3&&!has("bsi.corot.shell.materials")){wireError(rq,out,"UNSUPPORTED","engine lacks tensor checkpoint format");return;}
        options.allocationBudget=uint32_t(body.find("allocationBudget")->num);options.elementBudget=uint32_t(body.find("elementBudget")->num);
        if(!fracture_wire::same(options.expected,worldStamp_)||(importing&&(n!=size_t(body.find("bytes")->num)||n>options.byteBudget))){
            wireError(rq,out,"PROTOCOL_ERROR","checkpoint basis or byte count mismatch");return;}
        ReplyBuilder builder(uint32_t(world_.size()),0,BSI_STORAGE_F64);bsi_writer writer{&builder};json::Writer jw;
        if(!importing){const bsi_corot_checkpoint_view* view=nullptr;nativeFault_=true;
            const int status=engine_.vt->corot_checkpoint_export(inst_,&options,&view,&writer);
            if(status!=BSI_OK){nativeFault_=false;errorFromBuilder(out,rq,status,builder);return;}
            if(builder.hasError()||!view||view->struct_size<offsetof(bsi_corot_checkpoint_view,bytes)+sizeof(view->bytes)||view->format!=options.format||
               !fracture_wire::same(view->basis,options.expected)||!view->data||view->bytes<32||view->bytes>options.byteBudget){
                wireError(rq,out,"INTERNAL","invalid native checkpoint; reopen session");return;}
            std::vector<uint8_t> bytes(view->data,view->data+view->bytes);
            beginResponse(jw,rq,"response");jw.kv("status","exported");jw.key("basis");fracture_wire::writeStamp(jw,view->basis);
            jw.kv("format",int(view->format));jw.kv("bytes",(unsigned long long)bytes.size());jw.endObj();
            if(!wireHeader(rq,out,jw)){return;}out.payload=std::move(bytes);nativeFault_=false;return;
        }
        const bsi_corot_view* view=nullptr;nativeFault_=true;
        const int status=engine_.vt->corot_checkpoint_import(inst_,&options,payload,uint32_t(n),&view,&writer);
        if(status!=BSI_OK){nativeFault_=false;errorFromBuilder(out,rq,status,builder);return;}
        corot_wire::Pack pack;
        if(builder.hasError()||!view||view->struct_size<offsetof(bsi_corot_view,material)+sizeof(view->material)||
           !fracture_wire::same(view->basis,options.expected)||!corot_wire::analysis(view,view->options,artifactNamespace_,world_,owners_,vocab_,pack)){
            wireError(rq,out,"INTERNAL","invalid restored finite analysis; reopen session");return;}
        beginResponse(jw,rq,"response");jw.kv("status","restored");jw.key("basis");fracture_wire::writeStamp(jw,view->basis);
        jw.kv("artifactNamespace",fracture_wire::hex(view->artifactNamespace));jw.kv("generation",fracture_wire::hex(view->generation));
        jw.key("token");pdelta_wire::token(jw,view->token);jw.kv("fullyConverged",bool(view->fullyConverged));
        motion_wire::sections(jw,pack.table);jw.endObj();if(!wireHeader(rq,out,jw))return;
        out.payload=std::move(pack.bytes);nativeFault_=false;
    }
