package cn.kexin.rooms;

import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import javax.net.ssl.HttpsURLConnection;
import javax.crypto.Cipher;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.time.LocalDate;
import java.util.*;

public class SchoolClient {
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private Document pendingLogin;
    public record Reply(byte[] bytes, String contentType) { public String text(){return new String(bytes,StandardCharsets.UTF_8);} }
    public record Result(List<Protocol.Room> rooms, long fetchedAt) {}
    public record Search(Protocol.Config config, Result result) {}
    public static class CaptchaRequired extends IOException { public CaptchaRequired(){super("学校要求验证码，请输入后登录。");} }

    public void clear(){cookies.getCookieStore().removeAll();pendingLogin=null;}
    // Deliberately excludes course selection, grades, reservations and account mutations.
    private void checkUrl(URI uri,String method) throws IOException {
        if(!"https".equals(uri.getScheme())||!"jwglxxfwpt.hebeu.edu.cn".equals(uri.getHost())||(uri.getPort()!=-1&&uri.getPort()!=443)) throw new IOException("拒绝连接非学校教务地址。");
        String path=uri.getPath();
        boolean ok=path.equals("/xtgl/login_slogin.html") ||
            (method.equals("GET") && (path.equals("/xtgl/login_getPublicKey.html")||path.equals("/xtgl/index_initMenu.html")||path.equals("/kaptcha")||path.equals("/cdjy/cdjy_cxXqjc.html"))) ||
            path.equals("/cdjy/cdjy_cxQtlb.html") || path.equals("/cdjy/cdjy_cxKxcdlb.html");
        if(!ok)throw new IOException("学校登录或查询流程已变化，需要更新适配。");
        if(method.equals("POST")&&path.equals("/cdjy/cdjy_cxKxcdlb.html") && !Arrays.asList(Optional.ofNullable(uri.getQuery()).orElse("").split("&")).contains("doType=query"))throw new IOException("仅允许空教室查询。");
    }
    public Reply request(String path, Map<String,String> form) throws Exception {
        URI uri=URI.create(Protocol.ORIGIN+path); String method=form==null?"GET":"POST";
        byte[] body=form==null?null:encode(form).getBytes(StandardCharsets.UTF_8);
        for(int redirect=0;redirect<6;redirect++) {
            checkUrl(uri,method);
            HttpsURLConnection c=(HttpsURLConnection)uri.toURL().openConnection();
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setUseCaches(false);c.setRequestMethod(method);
            c.setRequestProperty("User-Agent","KexinRooms/1.0 (Android; Native)");
            c.setRequestProperty("Accept","application/json,text/html;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Cache-Control","no-cache, no-store");
            c.setRequestProperty("Referer",Protocol.ORIGIN+(uri.getPath().startsWith("/cdjy/")?Protocol.ROOMS:"/xtgl/login_slogin.html"));
            for(var h:cookies.get(uri,Map.of()).entrySet())c.setRequestProperty(h.getKey(),String.join("; ",h.getValue()));
            try {
                if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");c.setRequestProperty("Origin",Protocol.ORIGIN);c.setFixedLengthStreamingMode(body.length);try(OutputStream out=c.getOutputStream()){out.write(body);}}
                int status=c.getResponseCode();cookies.put(uri,c.getHeaderFields());
                if(status>=300&&status<400){String location=c.getHeaderField("Location");if(location==null)throw new IOException("学校返回无效跳转。");uri=uri.resolve(location);if(status==301||status==302||status==303){method="GET";body=null;}continue;}
                if(status==401)throw new Protocol.AuthExpired();
                if(status!=200)throw new IOException("教务系统暂时无法响应（HTTP "+status+"），请稍后重试。");
                try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>8_000_000)throw new IOException("学校响应过大，已停止读取。");}
                    return new Reply(out.toByteArray(),Optional.ofNullable(c.getContentType()).orElse(""));
                }
            } finally {c.disconnect();}
        }
        throw new IOException("登录跳转次数异常。");
    }
    public static String encode(Map<String,String> form) {
        try {StringJoiner s=new StringJoiner("&");for(var e:form.entrySet())s.add(URLEncoder.encode(e.getKey(),"UTF-8")+"="+URLEncoder.encode(e.getValue(),"UTF-8"));return s.toString();}
        catch(UnsupportedEncodingException impossible){throw new AssertionError(impossible);}
    }
    private JSONObject json(Reply r) throws Exception {
        String t=r.text().stripLeading();
        if(t.startsWith("<")){Protocol.document(t);throw new IOException("学校未返回有效查询数据，请稍后重试。");}
        try {return new JSONObject(t);}catch(JSONException e){throw new IOException("学校返回了无法识别的数据，本次未显示结果。");}
    }
    private boolean captchaShown(Document d){Element e=d.getElementById("yzm");if(e==null)return false;Element div=d.getElementById("yzmDiv");return div==null||!div.attr("style").replace(" ","").contains("display:none");}
    public void login(String username,String password,String captcha) throws Exception {
        if(captcha==null||pendingLogin==null)pendingLogin=Jsoup.parse(request("/xtgl/login_slogin.html",null).text());
        if(pendingLogin.selectFirst("input[name=yhm]")==null)throw new IOException("学校登录页面已变化。");
        if(captchaShown(pendingLogin)&&(captcha==null||captcha.isBlank()))throw new CaptchaRequired();
        Element token=pendingLogin.selectFirst("input[name=csrftoken]");
        if(token==null||token.val().isEmpty())throw new IOException("登录校验信息缺失，请重试。");
        JSONObject key=json(request("/xtgl/login_getPublicKey.html?time="+System.currentTimeMillis(),null));
        String encrypted=encrypt(password,key.getString("modulus"),key.getString("exponent"));
        Map<String,String> form=new LinkedHashMap<>();form.put("csrftoken",token.val());form.put("yhm",username);form.put("mm",encrypted);form.put("language","zh_CN");form.put("ydType","");
        if(captcha!=null&&!captcha.isBlank())form.put("yzm",captcha);
        Document response=Jsoup.parse(request("/xtgl/login_slogin.html?time="+System.currentTimeMillis(),form).text());
        if(response.selectFirst("input[name=yhm]")!=null){pendingLogin=response;if(captchaShown(response))throw new CaptchaRequired();throw new IOException("学校未接受此次登录，请核对账号密码；已停止自动重试。");}
        // A 200 or redirect alone is not proof of login: check the protected classroom page.
        Protocol.value(Protocol.document(request(Protocol.ROOMS,null).text()),"xnm");pendingLogin=null;
    }
    public static String encrypt(String password,String modulus,String exponent) throws Exception {
        KeyFactory factory=KeyFactory.getInstance("RSA");
        PublicKey key=factory.generatePublic(new RSAPublicKeySpec(new BigInteger(1,Base64.getDecoder().decode(modulus)),new BigInteger(1,Base64.getDecoder().decode(exponent))));
        Cipher cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding");cipher.init(Cipher.ENCRYPT_MODE,key);
        return Base64.getEncoder().encodeToString(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)));
    }
    public byte[] captcha() throws Exception {Reply r=request("/kaptcha?time="+System.currentTimeMillis(),null);if(!r.contentType().startsWith("image/"))throw new IOException("验证码图片暂不可用。");return r.bytes();}
    public Protocol.Config config() throws Exception {
        String html=request(Protocol.ROOMS,null).text();Document d=Protocol.document(html);
        String year=Protocol.value(d,"xnm"),term=Protocol.value(d,"xqm");
        JSONObject meta=json(request("/cdjy/cdjy_cxXqjc.html?gnmkdm=N2155&"+encode(Map.of("xqh_id",Protocol.CAMPUS,"xnm",year,"xqm",term)),null));
        JSONObject dates=json(request("/cdjy/cdjy_cxQtlb.html?gnmkdm=N2155",Map.of("xqh_id",Protocol.CAMPUS,"xnm",year,"xqm",term,"flag","0")));
        return Protocol.config(html,meta,dates);
    }
    /** Never accept a cached academic year from the UI. Re-read the school's current term per search. */
    public Search searchCurrent(LocalDate date,int from,int to,String building,String type) throws Exception {
        Protocol.Config current=config();
        return new Search(current,query(current,date,from,to,building,type));
    }
    public Result query(Protocol.Config c,LocalDate date,int from,int to,String building,String type) throws Exception {
        List<Protocol.Room> all=new ArrayList<>();Set<String> ids=new HashSet<>();int total=-1;
        for(int index=1;index<=30;index++){
            Protocol.Page p=Protocol.page(json(request(Protocol.ROOMS+"&doType=query",Protocol.query(c,date,from,to,building,type,index))));
            if(p.current()!=index || (total!=-1&&p.total()!=total))throw new IOException("查询期间学校数据发生变化，请刷新重试。");
            total=p.total();for(Protocol.Room r:p.rooms()){if(!ids.add(r.id()))throw new IOException("学校分页重复，请刷新重试。");all.add(r);}
            if(index>=p.pages()) {if(all.size()!=total)throw new IOException("学校数据没有完整返回，请刷新重试。");return new Result(List.copyOf(all),System.currentTimeMillis());}
            if(p.rooms().isEmpty())throw new IOException("学校分页数据不完整。");
        }
        throw new IOException("返回页数超过安全上限，本次未显示结果。");
    }
}
