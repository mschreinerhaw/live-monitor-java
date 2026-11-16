package com.live.monitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.dto.AlertContactPayload;
import com.live.monitor.entity.AlertChannel;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Contacts own recipient addresses; channels retain credentials and transport settings. */
@Service
public class AlertContactService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    public AlertContactService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public List<Map<String,Object>> list() {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT c.*, (SELECT COUNT(*) FROM alert_contact_binding b WHERE b.contact_id=c.id) AS binding_count FROM alert_contact c ORDER BY c.enabled DESC,c.name,c.id");
        List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> row:rows)result.add(lowerKeys(row));return result;
    }

    @Transactional
    public Map<String,Object> save(Long id, AlertContactPayload p) {
        String name=clean(p.name),email=clean(p.email),mobile=clean(p.mobile),wecom=clean(p.wecomUser);
        if(name.isEmpty() || (email.isEmpty() && mobile.isEmpty() && wecom.isEmpty()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请填写姓名及至少一种联系方式");
        if(wecom.matches(".*[\\s,;，；].*"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"企业微信成员 ID 不能包含空白或分隔符");
        if(id==null) {
            GeneratedKeyHolder key=new GeneratedKeyHolder();
            jdbc.update(connection->{PreparedStatement s=connection.prepareStatement("INSERT INTO alert_contact(name,email,mobile,wecom_user,description,enabled) VALUES(?,?,?,?,?,?)",new String[]{"id"});s.setString(1,name);s.setString(2,email);s.setString(3,mobile);s.setString(4,wecom);s.setString(5,p.description);s.setBoolean(6,!Boolean.FALSE.equals(p.enabled));return s;},key);
            id=Objects.requireNonNull(key.getKey()).longValue();
        } else {
            require(id);
            jdbc.update("UPDATE alert_contact SET name=?,email=?,mobile=?,wecom_user=?,description=?,enabled=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",name,email,mobile,wecom,p.description,!Boolean.FALSE.equals(p.enabled),id);
        }
        return lowerKeys(jdbc.queryForMap("SELECT * FROM alert_contact WHERE id=?",id));
    }

    @Transactional
    public void delete(long id) {
        require(id);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM alert_contact_binding WHERE contact_id=?",Integer.class,id)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"联系人已被项目或告警组引用，请先解除绑定；也可以停用联系人");
        jdbc.update("DELETE FROM alert_contact WHERE id=?",id);
    }

    private void require(long id) {
        if(jdbc.queryForList("SELECT id FROM alert_contact WHERE id=? FOR UPDATE",id).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"联系人不存在");
    }
    public List<Long> ids(String type,String owner) {
        return jdbc.queryForList("SELECT contact_id FROM alert_contact_binding WHERE owner_type=? AND owner_key=? ORDER BY contact_id",Long.class,type,owner);
    }
    @Transactional
    public void replace(String type,String owner,List<Long> raw) {
        if(raw==null)return; // Older clients do not erase address-book bindings.
        if(raw.size()>500)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"最多选择 500 位联系人");
        Set<Long> ids=new TreeSet<>();for(Long id:raw){if(id==null||id<=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"联系人 ID 无效");ids.add(id);}
        for(Long id:ids)require(id);
        clear(type,owner);
        for(Long id:ids)jdbc.update("INSERT INTO alert_contact_binding(owner_type,owner_key,contact_id) VALUES(?,?,?)",type,owner,id);
    }
    public void clear(String type,String owner) {jdbc.update("DELETE FROM alert_contact_binding WHERE owner_type=? AND owner_key=?",type,owner);}

    public AlertChannel bind(AlertChannel channel,List<Long> groupIds,List<Long> projectContacts) {
        Set<Long> selected=new TreeSet<>();boolean legacy=false;
        if(projectContacts!=null&&!projectContacts.isEmpty())selected.addAll(projectContacts);
        else for(Long group:groupIds){List<Long> contacts=ids("GROUP",String.valueOf(group));if(contacts.isEmpty())legacy=true;else selected.addAll(contacts);}
        if(selected.isEmpty()&&legacy)return copy(channel);
        AlertChannel copy=copy(channel);copy.recipientContextJson=context(selected,legacy);return copy;
    }

    public Map<String,Object> apply(AlertChannel channel,Map<String,Object> original) {
        if(channel.recipientContextJson==null)return original;
        Map<String,Object> context=parse(channel.recipientContextJson);
        boolean legacy=Boolean.TRUE.equals(context.get("include_legacy"));
        Map<String,Object> config=new LinkedHashMap<>(original);
        Map<String,LinkedHashSet<String>> values=new LinkedHashMap<>();
        for(String field:Arrays.asList("alert_email","alert_cc","alert_mobile","dingtalk_at_mobiles","wecom_mentioned_list","wecom_mentioned_mobiles")) {
            LinkedHashSet<String> addresses=new LinkedHashSet<>();
            if(legacy)addresses.addAll(Arrays.asList(Objects.toString(original.get(field),"").split("[,;，；\\s]+")));
            addresses.remove("");values.put(field,addresses);
        }
        List<Long> selectedIds=new ArrayList<>(contextIds(context));
        List<Map<String,Object>> selected=selectedIds.isEmpty()?Collections.emptyList():jdbc.queryForList("SELECT * FROM alert_contact WHERE enabled=1 AND id IN ("+String.join(",",Collections.nCopies(selectedIds.size(),"?"))+") ORDER BY id",selectedIds.toArray());
        for(Map<String,Object> c:selected) {
            add(values,"alert_email",c.get("email"));add(values,"alert_mobile",c.get("mobile"));
            add(values,"dingtalk_at_mobiles",c.get("mobile"));add(values,"wecom_mentioned_mobiles",c.get("mobile"));add(values,"wecom_mentioned_list",c.get("wecom_user"));
        }
        for(Map.Entry<String,LinkedHashSet<String>> row:values.entrySet())config.put(row.getKey(),String.join(",",row.getValue()));
        if(!legacy){config.put("dingtalk_at_all",false);config.put("wecom_at_all",false);}
        String type=Objects.toString(channel.channelType,"").toLowerCase(Locale.ROOT);
        boolean reachable="email".equals(type)?!values.get("alert_email").isEmpty():"sms".equals(type)?!values.get("alert_mobile").isEmpty():"dingtalk".equals(type)?!values.get("dingtalk_at_mobiles").isEmpty()||Boolean.TRUE.equals(config.get("dingtalk_at_all")):"wecom".equals(type)?!values.get("wecom_mentioned_list").isEmpty()||!values.get("wecom_mentioned_mobiles").isEmpty()||Boolean.TRUE.equals(config.get("wecom_at_all")):true;
        if(!reachable)throw new IllegalArgumentException("所选联系人已停用或没有适用于此渠道的联系方式");
        return config;
    }
    private static Map<String,Object> lowerKeys(Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>();for(Map.Entry<String,Object> entry:row.entrySet())result.put(entry.getKey().toLowerCase(Locale.ROOT),entry.getValue());return result;}
    private static void add(Map<String,LinkedHashSet<String>> values,String field,Object raw){String value=Objects.toString(raw,"").trim();if(!value.isEmpty())values.get(field).add(value);}
    private static String clean(String value){String s=Objects.toString(value,"").trim();if(s.contains("\r")||s.contains("\n"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"联系方式不能包含换行符");return s;}
    private static AlertChannel copy(AlertChannel source){AlertChannel c=new AlertChannel();c.id=source.id;c.channelName=source.channelName;c.channelType=source.channelType;c.configJson=source.configJson;c.enabled=source.enabled;c.createdAt=source.createdAt;c.recipientContextJson=source.recipientContextJson;return c;}
    private static Map<String,Object> parse(String value){try{Map<String,Object> result=JSON.readValue(value,new TypeReference<Map<String,Object>>(){});if(!(result.get("contact_ids") instanceof List)||!(result.get("include_legacy") instanceof Boolean))throw new IllegalArgumentException();return result;}catch(Exception e){throw new IllegalArgumentException("联系人投递配置无效");}}
    private static Set<Long> contextIds(Map<String,Object> c){Set<Long> ids=new TreeSet<>();for(Object id:(List<?>)c.get("contact_ids"))ids.add(Long.valueOf(id.toString()));return ids;}
    private static String context(Collection<Long> ids,boolean legacy){try{Map<String,Object> c=new LinkedHashMap<>();c.put("contact_ids",ids);c.put("include_legacy",legacy);return JSON.writeValueAsString(c);}catch(Exception e){throw new IllegalStateException(e);}}
    public static String mergeContexts(String first,String second){if(first==null&&second==null)return null;Set<Long> ids=new TreeSet<>();boolean legacy=first==null||second==null;for(String raw:new String[]{first,second})if(raw!=null){Map<String,Object> c=parse(raw);ids.addAll(contextIds(c));legacy|=Boolean.TRUE.equals(c.get("include_legacy"));}return context(ids,legacy);}
}
