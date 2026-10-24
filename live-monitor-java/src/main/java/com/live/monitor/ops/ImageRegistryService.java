package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ImageRegistryService {
    final OpsRepository repo;
    public ImageRegistryService(OpsRepository repo){this.repo=repo;}
    public Map<String,Object> save(Map<String,Object> v) {
        identifier(text(v,"id"));require(text(v,"address").matches("[a-zA-Z0-9][a-zA-Z0-9.:-]{1,253}"),"Registry domain[:port] required");
        require(text(v,"credential_ref").isEmpty()||text(v,"credential_ref").matches("[a-zA-Z0-9_.-]{1,64}"),"Use an agent-local Docker config reference");
        return repo.save("registry",map("id",text(v,"id"),"name",text(v,"name"),"address",text(v,"address"),
            "credential_ref",text(v,"credential_ref"),"revision",v.getOrDefault("revision",0)));
    }
}
