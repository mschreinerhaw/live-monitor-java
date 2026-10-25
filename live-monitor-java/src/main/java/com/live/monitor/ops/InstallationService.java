package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import com.live.monitor.service.CryptoService;
import com.live.monitor.mapper.HostMapper;
import com.live.monitor.entity.HostConfig;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/** Parameters come from a host-verified signed package, never from page business logic. */
@Service
public class InstallationService {
    private final OpsRepository repo;private final CryptoService crypto;private final HostMapper monitors;
    public InstallationService(OpsRepository repo,CryptoService crypto,HostMapper monitors){this.repo=repo;this.crypto=crypto;this.monitors=monitors;}
    public synchronized Map<String,Object> planInput(Map<String,Object> installation,Map<String,Object> supplied) {
        String id=text(installation,"id");Map<String,Object> schema=object(installation,"parameters"),values=new TreeMap<>();
        require(schema.keySet().containsAll(supplied.keySet()),"Unknown installation parameter");
        Map<String,Object> privateRecord=repo.list("installer_private").stream().filter(row->id.equals(text(row,"id"))).findFirst().orElse(map());
        Map<String,Object> previous=privateRecord.containsKey("cipher")?parse(crypto.decrypt(text(privateRecord,"cipher"))):map();
        for(String key:schema.keySet()){
            Map<String,Object> spec=object(schema,key);String type=text(spec,"type");
            if("generated".equals(type)){
                require(!supplied.containsKey(key),"Generated installation values cannot be overridden");
                if("aes-gcm".equals(text(spec,"generation")))continue;
                if(previous.containsKey(key))values.put(key,previous.get(key));
                else if(!"aes-gcm".equals(text(spec,"generation"))){require(Arrays.asList("","random").contains(text(spec,"generation")),"Unsupported parameter generation");values.put(key,UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""));}
            } else {
                Object value=supplied.getOrDefault(key,spec.getOrDefault("default",""));
                if("integer".equals(type))require(value instanceof Number&&((Number)value).longValue()>=number(spec,"min",1)&&((Number)value).longValue()<=number(spec,"max",65535),"Installation integer parameter outside allowed range");
                else require(value instanceof String&&((String)value).length()<=number(spec,"max_length","secret".equals(type)?8192:1024),"Installation text parameter exceeds limit");
                require(Boolean.FALSE.equals(spec.get("required"))||!Objects.toString(value,"").isEmpty(),"Required installation parameter missing: "+key);
                values.put(key,value);
            }
        }
        for(String key:schema.keySet())if(!values.containsKey(key)){
            Map<String,Object> spec=object(schema,key);require("aes-gcm".equals(text(spec,"generation")),"Unsupported parameter generation");
            require(values.containsKey(text(spec,"source"))&&values.containsKey(text(spec,"key")),"Generated credential dependencies missing");
            String source=text(spec,"source"),keyRef=text(spec,"key");
            if(previous.containsKey(key)&&Objects.equals(values.get(source),previous.get(source))&&Objects.equals(values.get(keyRef),previous.get(keyRef)))values.put(key,previous.get(key));
            else values.put(key,encryptEnvelope(Objects.toString(values.get(source)),Objects.toString(values.get(keyRef))));
        }
        require(utf8Length(json(values))<12000,"Installation parameters exceed request limit");
        privateRecord.putAll(map("id",id,"host_id",text(installation,"host_id"),"cipher",crypto.encrypt(json(values))));repo.save("installer_private",privateRecord);
        Map<String,Object> host=repo.get("host",text(installation,"host_id"));HostConfig connection=monitors.findHost(((Number)host.get("monitor_host_id")).longValue());require(connection!=null,"Host connection missing");
        return map("encrypted_parameters",crypto.encrypt(json(values)),"parameter_hash",OpsTaskService.hash(json(values)),"public_host",connection.ip);
    }
    private String encryptEnvelope(String value,String key) {
        try {byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(key.trim().getBytes(StandardCharsets.UTF_8)),"AES"),new GCMParameterSpec(128,iv));
            return "ENC("+Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)))+")";
        }catch(Exception e){throw new IllegalStateException("Unable to generate installation credentials");}
    }
    public Map<String,Object> execute(OpsAgentGateway agent,String action,Map<String,Object> installation,Map<String,Object> input,String task) {
        Map<String,Object> payload=map("installation_id",text(installation,"id"),"bundle_id",text(installation,"bundle_id"));
        if("install_plan".equals(action))payload.putAll(map("parameters",parse(crypto.decrypt(text(input,"encrypted_parameters"))),"public_host",text(input,"public_host")));
        else payload.put("plan_id",text(input,"plan_id"));
        Map<String,Object> result=agent.call(repo.get("host",text(installation,"host_id")),action,payload,task);
        repo.transaction().execute(transaction->{
        Map<String,Object> current=repo.get("installer",text(installation,"id"));
        if("install_apply".equals(action)&&"INSTALLED".equals(text(current,"status")))return null;
        if("install_plan".equals(action))current.putAll(map("status",flag(result,"ready")?"READY":"CHECK_FAILED","plan_id",text(result,"plan_id")));
        else {
            String id=text(installation,"id");Map<String,Object> spec=map("id",id,"name",text(installation,"name"),"host_id",text(installation,"host_id"),"environment",text(installation,"environment"),"package_ref",text(result,"package_ref"),"package_sha256",text(result,"package_sha256"));
            StackManagementService.validate(spec);String version=UUID.randomUUID().toString();
            repo.save("stack_version",map("id",version,"stack_id",id,"host_id",text(spec,"host_id"),"environment",text(spec,"environment"),"spec",spec,"action",action,"task_id",task,"created_at",System.currentTimeMillis()));
            Map<String,Object> stack=new LinkedHashMap<>(spec);stack.put("desired_state",object(result,"snapshot").getOrDefault("desired_state","RUNNING"));stack.put("active_version",version);repo.save("stack",stack);
            repo.save("stack_snapshot",map("id",id,"stack_id",id,"host_id",text(spec,"host_id"),"environment",text(spec,"environment"),"observed_at",System.currentTimeMillis(),"data",object(result,"snapshot")));
            current.putAll(map("status","INSTALLED","entrypoints",result.get("entrypoints"),"stack_id",id));
        }
        repo.save("installer",current);return null;});return result;
    }
}
