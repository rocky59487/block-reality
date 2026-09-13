// Included in SessionImpl. Arc advancement consumes the committed load catalog.
    void corotArcRequest(const Request& rq,size_t n,Reply& out) {
        if(!has("bsi.corot.arc")||engine_.vt->abi_version<9||!engine_.vt->corot_arc_advance){
            wireError(rq,out,"UNSUPPORTED","engine lacks whole-world arc continuation");return;}
        if(!fractureBody(rq,out,"corot.arc.advance.body",n,true))return;
        if(!identified_){wireError(rq,out,"NO_WORLD","no identified world");return;}
        const auto& b=*rq.body;bsi_corot_arc_request input{};input.struct_size=sizeof(input);
        input.expected=fracture_wire::stamp(*b.find("expected"));input.initialAnalysis=pdelta_wire::token(*b.find("initialAnalysis"));
        input.radius=b.find("radius")->num;input.minRadius=b.find("minRadius")->num;
        input.lengthScale=b.find("lengthScale")->num;input.loadScale=b.find("loadScale")->num;
        input.relativeTolerance=b.find("relativeTolerance")->num;input.forceTolerance=b.find("forceTolerance")->num;
        input.momentTolerance=b.find("momentTolerance")->num;input.linearTolerance=b.find("linearTolerance")->num;
        input.pathTolerance=b.find("pathTolerance")->num;input.maxIterations=uint32_t(b.find("maxIterations")->num);
        input.maxAttempts=uint32_t(b.find("maxAttempts")->num);input.maxBacktracks=uint32_t(b.find("maxBacktracks")->num);
        input.linearIterations=uint32_t(b.find("linearIterations")->num);input.restart=uint32_t(b.find("restart")->num);
        input.initialDirection=int32_t(b.find("initialDirection")->num);
        if(!fracture_wire::same(input.expected,worldStamp_)){wireError(rq,out,"PROTOCOL_ERROR","arc basis mismatch");return;}
        ReplyBuilder builder(uint32_t(world_.size()),0,BSI_STORAGE_F64);bsi_writer writer{&builder};
        const bsi_corot_arc_view* arc=nullptr;nativeFault_=true;
        const int status=engine_.vt->corot_arc_advance(inst_,&input,&arc,&writer);
        if(status!=BSI_OK){nativeFault_=false;errorFromBuilder(out,rq,status,builder);return;}
        corot_wire::Pack pack;
        if(builder.hasError()||!arc||arc->struct_size<offsetof(bsi_corot_arc_view,analysis)+sizeof(arc->analysis)||arc->reserved||
           !std::isfinite(arc->radius)||arc->radius<input.minRadius||arc->radius>input.radius||
           !std::isfinite(arc->constraintResidual)||arc->constraintResidual<0||arc->constraintResidual>1e-10||
           !arc->analysis||arc->analysis->struct_size<offsetof(bsi_corot_view,material)+sizeof(arc->analysis->material)||
           !arc->analysis->fullyConverged||!fracture_wire::same(arc->analysis->basis,input.expected)||
           !corot_wire::analysis(arc->analysis,arc->analysis->options,artifactNamespace_,world_,owners_,vocab_,pack)){
            wireError(rq,out,"INTERNAL","invalid native arc result; reopen session");return;}
        const auto& v=*arc->analysis;json::Writer jw;beginResponse(jw,rq,"response");jw.kv("status","advanced");
        jw.key("basis");fracture_wire::writeStamp(jw,v.basis);jw.kv("artifactNamespace",fracture_wire::hex(v.artifactNamespace));
        jw.kv("generation",fracture_wire::hex(v.generation));jw.key("token");pdelta_wire::token(jw,v.token);jw.kv("fullyConverged",true);
        // The finite extension's scalar controls are JSON numbers, like its input.
        auto number=[&](const char* name,double value){char text[64];const auto r=std::to_chars(text,text+sizeof(text),value,std::chars_format::general,17);
            jw.key(name).raw(std::string(text,r.ptr));};
        number("loadFactor",v.options.loadFactor);number("radius",arc->radius);number("constraintResidual",arc->constraintResidual);
        motion_wire::sections(jw,pack.table);jw.endObj();if(!wireHeader(rq,out,jw))return;
        out.payload=std::move(pack.bytes);nativeFault_=false;
    }
