package com.live.monitor.service;

import com.live.monitor.config.DatabaseDialect;
import com.live.monitor.entity.TUser;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CalendarService {
    private final JdbcTemplate jdbc;
    private final DatabaseDialect dialect;
    private final LoginAuditLogService audit;
    public CalendarService(JdbcTemplate jdbc, DatabaseDialect dialect, LoginAuditLogService audit) {
        this.jdbc=jdbc; this.dialect=dialect; this.audit=audit;
    }
    public String table(String kind) {
        if (!Arrays.asList("trading","working").contains(kind)) throw bad("日历类型无效");
        return "t_ld_"+kind+"_calendar";
    }
    private String business(String kind) { table(kind); return "trading".equals(kind)?"JYR":"GZR"; }
    private List<String> columns(String kind) { return Arrays.asList("ZRR",business(kind),"ND","JD","YF_XH","XQ","NY","NZ","XQ_XH"); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    public Map<String,Object> list(String kind,Integer from,Integer to,int page,int size) {
        String table=table(kind); if(from!=null) date(from); if(to!=null) date(to);
        if(from!=null && to!=null && from>to) throw bad("开始日期不能晚于结束日期");
        List<Object> args=new ArrayList<>(); String where=" WHERE 1=1";
        if(from!=null){where+=" AND ZRR>=?";args.add(from);} if(to!=null){where+=" AND ZRR<=?";args.add(to);}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+where,Long.class,args.toArray());
        size=Math.max(1,Math.min(100,size)); page=Math.max(1,Math.min(page,(int)Math.max(1,(total+size-1)/size)));
        List<Object> query=new ArrayList<>(args);query.add(size);query.add((page-1)*size);
        List<Map<String,Object>> rows=jdbc.query("SELECT * FROM "+table+where+" ORDER BY ZRR,"+business(kind)+" LIMIT ? OFFSET ?",(rs,n)->{
            Map<String,Object> row=new LinkedHashMap<>();for(String c:columns(kind))row.put(c.toLowerCase(Locale.ROOT),rs.getObject(c));return row;
        },query.toArray());
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",rows);result.put("total",total);result.put("page",page);result.put("page_size",size);return result;
    }
    /** Exact, explicit calendar lookup. Never infer missing mappings from weekdays. */
    public Map<String,Object> resolve(String kind,int natural) {
        date(natural);List<Integer> values=jdbc.queryForList("SELECT "+business(kind)+" FROM "+table(kind)+" WHERE ZRR=? ORDER BY "+business(kind),Integer.class,natural);
        Map<String,Object> result=new LinkedHashMap<>();result.put("natural_date",natural);result.put("values",values);result.put("source",table(kind));
        if(values.size()==1){try{result.put("etl_date",date(values.get(0)).toString());result.put("status","RESOLVED");}catch(ResponseStatusException ex){result.put("status","VALUE_NOT_DATE");}}
        else result.put("status",values.isEmpty()?"MISSING":"AMBIGUOUS");return result;
    }
    @Transactional
    public void save(String kind,Map<String,Object> input,TUser user,String ip) {
        write(kind,normalize(kind,input));audit.record(user,"CALENDAR_SAVE "+kind+" ZRR="+input.get("zrr"),ip);
    }
    @Transactional
    public void delete(String kind,int natural,int mapped,TUser user,String ip) {
        date(natural);int count=jdbc.update("DELETE FROM "+table(kind)+" WHERE ZRR=? AND "+business(kind)+"=?",natural,mapped);
        if(count==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"日历记录不存在");
        audit.record(user,"CALENDAR_DELETE "+kind+" ZRR="+natural+" "+business(kind)+"="+mapped,ip);
    }
    private Map<String,Integer> normalize(String kind,Map<String,?> input){
        Map<String,Integer> row=new LinkedHashMap<>();Set<String> allowed=new HashSet<>(columns(kind));
        for(String key:input.keySet())if(!allowed.contains(key.toUpperCase(Locale.ROOT)))throw bad("不支持的字段："+key);
        for(String key:columns(kind)){
            Object value=input.containsKey(key)?input.get(key):input.get(key.toLowerCase(Locale.ROOT));
            if(value==null || "".equals(value))row.put(key,null);
            else try{String text=value.toString();if(!text.matches("-?\\d+"))throw new NumberFormatException();row.put(key,Integer.valueOf(text));}catch(NumberFormatException ex){throw bad(key+" 必须是整数或 NULL");}
        }
        if(row.get("ZRR")==null || row.get(business(kind))==null)throw bad("自然日与业务日不能为空");date(row.get("ZRR"));date(row.get(business(kind)));if(row.get("ND")!=null && row.get("ND")!=row.get("ZRR")/10000)throw bad("ND 必须等于自然日所属年度");return row;
    }
    private void write(String kind,Map<String,Integer> row){
        List<String> columns=columns(kind);String names=String.join(",",columns),values=String.join(",",Collections.nCopies(columns.size(),"?"));
        String sql;
        if(dialect.isMysql()){List<String> updates=new ArrayList<>();for(String c:columns.subList(2,columns.size()))updates.add(c+"=VALUES("+c+")");sql="INSERT INTO "+table(kind)+" ("+names+") VALUES ("+values+") ON DUPLICATE KEY UPDATE "+String.join(",",updates);}
        else sql="MERGE INTO "+table(kind)+" ("+names+") KEY(ZRR,"+business(kind)+") VALUES ("+values+")";
        jdbc.update(sql,columns.stream().map(row::get).toArray());
    }
    public Map<String,Object> preview(String kind,String sql){
        Set<Integer> years=new LinkedHashSet<>();List<Map<String,Integer>> rows=parse(kind,sql,years);Map<String,Object> result=new LinkedHashMap<>();result.put("row_count",rows.size());result.put("delete_years",years);long removed=0;for(Integer year:years)removed+=jdbc.queryForObject("SELECT COUNT(*) FROM "+table(kind)+" WHERE ND=?",Long.class,year);result.put("delete_count",removed);result.put("sample",rows.subList(0,Math.min(20,rows.size())));result.put("mode",years.isEmpty()?"按复合主键更新或新增，保留其他记录":"先删除指定年度，再按复合主键写入；其他年度保留");return result;
    }
    @Transactional
    public int importSql(String kind,String sql,TUser user,String ip){
        Set<Integer> years=new LinkedHashSet<>();List<Map<String,Integer>> rows=parse(kind,sql,years);for(Integer year:years)jdbc.update("DELETE FROM "+table(kind)+" WHERE ND=?",year);for(Map<String,Integer> row:rows)write(kind,row);
        audit.record(user,"CALENDAR_IMPORT "+kind+" rows="+rows.size()+" replace_years="+years,ip);return rows.size();
    }
    /** Restricted data reader: SQL text is never executed. */
    List<Map<String,Integer>> parse(String kind,String sql){return parse(kind,sql,new LinkedHashSet<Integer>());}
    private List<Map<String,Integer>> parse(String kind,String sql,Set<Integer> years){
        table(kind);if(sql==null || sql.length()>2_000_000)throw bad("SQL 文件为空或超过 2 MB 限制");
        String source=sql.replace("\uFEFF","").replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)--[^\\r\\n]*", "");
        List<Map<String,Integer>> rows=new ArrayList<>();Set<String> keys=new HashSet<>();
        for(String statement:source.split(";")){
            String text=statement.trim();if(text.isEmpty())continue;
            Matcher deletion=Pattern.compile("(?is)^DELETE\\s+FROM\\s+`?([a-zA-Z0-9_]+)`?\\s+WHERE\\s+`?ND`?\\s*=\\s*([1-9][0-9]{3})\\s*$").matcher(text);
            if(deletion.matches()){if(!table(kind).equalsIgnoreCase(deletion.group(1)))throw bad("删除语句必须限定所选日历表");if(!rows.isEmpty())throw bad("年度删除语句必须放在所有 INSERT 之前");years.add(Integer.valueOf(deletion.group(2)));continue;}
            Matcher ddl=Pattern.compile("(?is)^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([a-zA-Z0-9_]+)`?\\s*\\(.*\\).*$").matcher(text);
            if(ddl.matches()){if(!table(kind).equalsIgnoreCase(ddl.group(1)))throw bad("文件包含其他表的建表语句");continue;}
            Matcher insert=Pattern.compile("(?is)^INSERT\\s+INTO\\s+`?([a-zA-Z0-9_]+)`?\\s*(?:\\(([^)]+)\\))?\\s+VALUES\\s*(.+)$").matcher(text);
            if(!insert.matches() || !table(kind).equalsIgnoreCase(insert.group(1)))throw bad("仅支持日历表建表、按 ND=年度删除和 INSERT INTO ... VALUES 语句");
            List<String> names=insert.group(2)==null?columns(kind):new ArrayList<>();
            if(insert.group(2)!=null)for(String c:insert.group(2).split(","))names.add(c.trim().replace("`","").toUpperCase(Locale.ROOT));
            if(new HashSet<>(names).size()!=names.size())throw bad("字段重复");
            Matcher tuples=Pattern.compile("\\(([^()]*)\\)").matcher(insert.group(3));int end=0;
            while(tuples.find()){
                if(!insert.group(3).substring(end,tuples.start()).trim().matches(end==0?"":","))throw bad("INSERT 数据格式不正确");
                String[] cells=tuples.group(1).split(",",-1);if(cells.length!=names.size())throw bad("字段数与数据列数不一致");
                Map<String,Object> row=new LinkedHashMap<>();for(int i=0;i<cells.length;i++){String value=cells[i].trim();if(value.matches("'[-0-9]+'"))value=value.substring(1,value.length()-1);row.put(names.get(i),"NULL".equalsIgnoreCase(value)?null:value);}
                Map<String,Integer> normalized=normalize(kind,row);String key=normalized.get("ZRR")+":"+normalized.get(business(kind));if(!keys.add(key))throw bad("文件内存在重复复合主键："+key);
                rows.add(normalized);if(rows.size()>20000)throw bad("单次最多导入 20000 行");end=tuples.end();
            }
            if(end==0 || !insert.group(3).substring(end).trim().isEmpty())throw bad("INSERT 语句包含不支持的内容");
        }
        if(rows.isEmpty() && years.isEmpty())throw bad("未找到可导入的日历记录");return rows;
    }
    public Map<String,String> splitFile(String sql) {
        if(sql==null || sql.length()>2_000_000)throw bad("SQL 文件为空或超过 2 MB");
        String source=sql.replace("\uFEFF","").replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)--[^\\r\\n]*", "");
        Map<String,String> parts=new LinkedHashMap<>();
        for(String statement:source.split(";")){
            if(statement.trim().isEmpty())continue;
            Matcher m=Pattern.compile("(?is)^\\s*(?:INSERT\\s+INTO|DELETE\\s+FROM|CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?)\\s+`?(t_ld_(trading|working)_calendar)`?\\b.*$").matcher(statement);
            if(!m.matches())throw bad("文件包含不支持的语句或其他表；仅允许两张日历表的数据更新");
            String kind=m.group(2).toLowerCase(Locale.ROOT);parts.put(kind,parts.getOrDefault(kind,"")+statement+";\n");
        }
        if(parts.isEmpty())throw bad("未找到日历数据");return parts;
    }
    public Map<String,Object> previewFile(String sql){Map<String,Object> result=new LinkedHashMap<>();for(Map.Entry<String,String> part:splitFile(sql).entrySet())result.put(part.getKey(),preview(part.getKey(),part.getValue()));return result;}
    @Transactional public Map<String,Object> importFile(String sql,TUser user,String ip){
        Map<String,String> parts=splitFile(sql);for(Map.Entry<String,String> part:parts.entrySet())parse(part.getKey(),part.getValue());
        Map<String,Object> result=new LinkedHashMap<>();for(Map.Entry<String,String> part:parts.entrySet())result.put(part.getKey(),importSql(part.getKey(),part.getValue(),user,ip));return result;
    }
    private LocalDate date(int value){try{String text=Integer.toString(value);if(text.length()!=8)throw new IllegalArgumentException();return LocalDate.parse(text,DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT));}catch(Exception ex){throw bad("日期必须是有效的 YYYYMMDD："+value);}}
}
