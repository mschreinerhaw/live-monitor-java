package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class OpsRepository {
    final JdbcTemplate jdbc;
    final TransactionTemplate tx;
    public OpsRepository(JdbcTemplate jdbc,TransactionTemplate tx) { this.jdbc=jdbc;this.tx=tx; }
    public JdbcTemplate jdbc(){return jdbc;}
    public TransactionTemplate transaction(){return tx;}
    @PostConstruct
    public void migrate() {
        new ResourceDatabasePopulator(new ClassPathResource("db/ops/V001__container_ops.sql")).execute(jdbc.getDataSource());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ops_migration WHERE version=1",Integer.class)==0)
            jdbc.update("INSERT INTO ops_migration(version,applied_at) VALUES(1,?)",System.currentTimeMillis());
        new ResourceDatabasePopulator(new ClassPathResource("db/ops/V002__task_events.sql")).execute(jdbc.getDataSource());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ops_migration WHERE version=2",Integer.class)==0)
            jdbc.update("INSERT INTO ops_migration(version,applied_at) VALUES(2,?)",System.currentTimeMillis());
    }
    public List<Map<String,Object>> list(String kind) {
        return jdbc.query("SELECT document,revision FROM ops_resource WHERE kind=? ORDER BY updated_at DESC",
            (r,n)->{Map<String,Object> d=parse(r.getString(1));d.put("revision",r.getLong(2));return d;},kind);
    }
    public Map<String,Object> get(String kind,String id) {
        List<Map<String,Object>> found=jdbc.query("SELECT document,revision FROM ops_resource WHERE kind=? AND id=?",
            (r,n)->{Map<String,Object> d=parse(r.getString(1));d.put("revision",r.getLong(2));return d;},kind,id);
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Resource not found");
        return found.get(0);
    }
    public synchronized Map<String,Object> save(String kind,Map<String,Object> doc) {
        String id=identifier(text(doc,"id"));long revision=((Number)doc.getOrDefault("revision",0)).longValue();
        String encoded=json(doc);require(utf8Length(encoded)<48000,"Resource document exceeds UTF-8 byte limit");
        if(revision==0) {
            try { jdbc.update("INSERT INTO ops_resource(id,kind,document,revision,updated_at) VALUES(?,?,?,1,?)",id,kind,encoded,System.currentTimeMillis()); }
            catch(DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Resource already exists"); }
        } else if(jdbc.update("UPDATE ops_resource SET document=?,revision=revision+1,updated_at=? WHERE id=? AND kind=? AND revision=?",
                encoded,System.currentTimeMillis(),id,kind,revision)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Resource changed; reload before saving");
        return get(kind,id);
    }
    public boolean lock(String resource,String owner) {
        try {jdbc.update("INSERT INTO ops_lock(resource_id,owner) VALUES(?,?)",resource,owner);return true;}
        catch(DuplicateKeyException e){return false;}
    }
    public void unlock(String resource,String owner) { jdbc.update("DELETE FROM ops_lock WHERE resource_id=? AND owner=?",resource,owner); }
    public List<Map<String,Object>> tasks() { return jdbc.queryForList("SELECT * FROM ops_task ORDER BY created_at DESC LIMIT 200"); }
    public Map<String,Object> task(String id) {
        List<Map<String,Object>> found=jdbc.queryForList("SELECT * FROM ops_task WHERE id=?",id);
        if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Task not found");return found.get(0);
    }
    public void audit(String actor,String action,String resource,String task,String outcome) {
        jdbc.update("INSERT INTO ops_audit(id,actor,action,resource_id,task_id,outcome,created_at) VALUES(?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),actor,action,resource,task,outcome,System.currentTimeMillis());
    }
    public void taskEvent(String task,int progress,String message){jdbc.update("INSERT INTO ops_task_event(id,task_id,progress,message,created_at) VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),task,progress,message,System.currentTimeMillis());}
}
