package cn.kexin.rooms;

import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import java.io.IOException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** School-specific, read-only contract verified against HEBEU on 2026-09-21. */
public final class Protocol {
    public static final String ORIGIN = "https://jwglxxfwpt.hebeu.edu.cn";
    public static final String CAMPUS = "106";
    public static final String ROOMS = "/cdjy/cdjy_cxKxcdlb.html?gnmkdm=N2155";
    public record Option(String id, String label) { @Override public String toString() { return label; } }
    public record Period(int number, String time) { @Override public String toString() { return "第"+number+"节  "+time; } }
    public record Config(String year, String term, String label, LocalDate start, int firstWeek,
                         Set<Integer> weeks, List<Option> buildings, List<Option> types, List<Period> periods) {
        public int week(LocalDate date) {
            long days = ChronoUnit.DAYS.between(start, date);
            int week = (int)Math.floorDiv(days, 7)+firstWeek;
            if (days < 0 || !weeks.contains(week)) throw new IllegalArgumentException("所选日期不在本学期可查询周次内，请更换日期。");
            return week;
        }
    }
    public record Room(String id, String name, String building, String type, String seats, String bookable) {}
    public record Page(int total, int pages, int current, List<Room> rooms) {}

    public static Document document(String html) throws IOException {
        Document d = Jsoup.parse(html);
        if (d.selectFirst("input[name=yhm]") != null) throw new AuthExpired();
        return d;
    }
    public static String value(Document d, String id) throws IOException {
        Element e=d.getElementById(id);
        if(e==null || e.val().isBlank()) throw new IOException("学校页面字段已变更（"+id+"），本次未显示查询结果。");
        return e.val();
    }
    public static Config config(String html, JSONObject metadata, JSONObject termData) throws Exception {
        Document d=document(html);
        Element campus=d.selectFirst("select#xqh_id option[value=106]");
        if(campus==null || !campus.text().contains("科信")) throw new IOException("无法确认科信校区，已停止查询。");
        String year=value(d,"xnm"), term=value(d,"xqm");
        Element selected=d.selectFirst("#dm_cx option[value="+year+"-"+term+"]");
        if(selected==null) throw new IOException("无法确认当前学期。");
        JSONObject dates=termData.getJSONObject("dqzcxq");
        LocalDate start=LocalDate.parse(dates.getString("ZXRQ"));
        if(!dates.getString("ZQST").equals("1") || start.getDayOfWeek().getValue()!=1) throw new IOException("学校校历结构变化，需重新适配。");
        Set<Integer> weeks=new TreeSet<>();
        JSONArray wa=termData.getJSONArray("nxqzcList");
        for(int i=0;i<wa.length();i++) { JSONObject w=wa.getJSONObject(i); if(!w.optString("zczt").equals("0")) weeks.add(w.getInt("dxqzc")); }
        if(weeks.isEmpty() || Collections.max(weeks)>52) throw new IOException("学校没有返回有效周次。");
        List<Option> buildings=new ArrayList<>(); buildings.add(new Option("","全部教学楼"));
        JSONArray ba=metadata.getJSONArray("lhList");
        for(int i=0;i<ba.length();i++) { JSONObject b=ba.getJSONObject(i); if(b.has("XQH_ID")&&!CAMPUS.equals(b.getString("XQH_ID"))) throw new IOException("教学楼校区不匹配。"); buildings.add(new Option(b.getString("JXLDM"),b.getString("JXLMC"))); }
        List<Option> types=new ArrayList<>();
        for(Element o:d.select("#cdlb_id option")) types.add(new Option(o.val(),o.val().isEmpty()?"全部场地类别":o.text()));
        List<Period> periods=new ArrayList<>(); JSONArray pa=metadata.getJSONArray("jcList");
        for(int i=0;i<pa.length();i++) { JSONObject p=pa.getJSONObject(i); int n=p.getInt("JCMC"); if(n<1||n>30) throw new IOException("无效节次。"); periods.add(new Period(n,p.getString("SJD"))); }
        if(periods.isEmpty()) throw new IOException("学校没有返回上课节次。");
        return new Config(year,term,selected.text(),start,dates.getInt("ZXZC"),weeks,buildings,types,periods);
    }
    public static Map<String,String> query(Config c, LocalDate date, int from, int to, String building, String type, int page) {
        if(from>to||from<1||to>30) throw new IllegalArgumentException("结束节次不能早于开始节次。");
        Set<Integer> valid=new HashSet<>(); for(Period p:c.periods()) valid.add(p.number());
        long bits=0; for(int i=from;i<=to;i++) { if(!valid.contains(i)) throw new IllegalArgumentException("节次不在学校配置中。"); bits |= 1L<<(i-1); }
        if(c.buildings().stream().noneMatch(o->o.id().equals(building)) || c.types().stream().noneMatch(o->o.id().equals(type))) throw new IllegalArgumentException("查询条件已经失效，请刷新。");
        Map<String,String> m=new LinkedHashMap<>();
        m.put("xqh_id",CAMPUS);m.put("xnm",c.year());m.put("xqm",c.term());m.put("jyfs","0");
        m.put("zcd",Long.toString(1L<<(c.week(date)-1)));m.put("xqj",Integer.toString(date.getDayOfWeek().getValue()));m.put("jcd",Long.toString(bits));
        m.put("lh",building);m.put("cdlb_id",type);
        for(String k:List.of("cdejlb_id","qszws","jszws","cdmc","cdjylx","sfbhkc"))m.put(k,"");
        m.put("queryModel.showCount","100");m.put("queryModel.currentPage",Integer.toString(page));
        m.put("queryModel.sortName","cdbh");m.put("queryModel.sortOrder","asc");m.put("_search","false");m.put("nd",Long.toString(System.currentTimeMillis()));
        return m;
    }
    public static Page page(JSONObject j) throws Exception {
        int total=j.getInt("totalCount"), pages=j.getInt("totalPage"), current=j.getInt("currentPage");
        if(total<0||total>3000||pages<0||pages>30||current<1) throw new IOException("学校返回的分页信息异常。");
        List<Room> rooms=new ArrayList<>(); JSONArray items=j.getJSONArray("items");
        for(int i=0;i<items.length();i++) { JSONObject r=items.getJSONObject(i);
            if(!CAMPUS.equals(r.optString("xqh_id"))||!r.optString("xqmc").contains("科信"))throw new IOException("返回了非科信校区数据，已拒绝显示。");
            String name=r.getString("cdmc"), id=r.getString("cd_id");
            if(name.isBlank()||id.isBlank())throw new IOException("学校返回的教室信息不完整。");
            rooms.add(new Room(id,name,r.optString("jxlmc","未标注楼号"),r.optString("cdlbmc","未标注类型"),r.optString("zws","未提供"),r.optString("sfkjy","未提供")));
        }
        return new Page(total,pages,current,rooms);
    }
    public static class AuthExpired extends IOException { public AuthExpired(){super("登录已失效，请重新登录。");} }
}
