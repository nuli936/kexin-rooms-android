package cn.kexin.rooms;

import org.junit.Test;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import javax.crypto.Cipher;
import static org.junit.Assert.*;

public class ProtocolTest {
    private String fixture(String name)throws Exception{try(var in=getClass().getResourceAsStream("/"+name)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    private Protocol.Config config()throws Exception {return Protocol.config(fixture("config.html"),new JSONObject(fixture("metadata.json")),new JSONObject(fixture("term.json")));}
    @Test public void observedSchoolConfigIsParsed()throws Exception{var c=config();assertEquals("2026",c.year());assertEquals("3",c.term());assertEquals(10,c.periods().size());assertEquals("08:30-09:15",c.periods().get(0).time());assertEquals(19,c.weeks().size());}
    @Test public void mondayMappingAndCampusMatchObservedRequest()throws Exception{var m=Protocol.query(config(),LocalDate.of(2026,9,21),1,2,"","",1);assertEquals("106",m.get("xqh_id"));assertEquals("8",m.get("zcd"));assertEquals("1",m.get("xqj"));assertEquals("3",m.get("jcd"));}
    @Test public void sundayAndNextMondayStayInCorrectWeek()throws Exception{var c=config();assertEquals(4,c.week(LocalDate.of(2026,9,27)));assertEquals(5,c.week(LocalDate.of(2026,9,28)));assertEquals("7",Protocol.query(c,LocalDate.of(2026,9,27),9,10,"","",1).get("xqj"));assertEquals("768",Protocol.query(c,LocalDate.of(2026,9,27),9,10,"","",1).get("jcd"));}
    @Test public void unsupportedDatesAndReversedPeriodsRejected()throws Exception{var c=config();assertThrows(IllegalArgumentException.class,()->c.week(LocalDate.of(2026,8,30)));assertThrows(IllegalArgumentException.class,()->c.week(LocalDate.of(2027,1,11)));assertThrows(IllegalArgumentException.class,()->Protocol.query(c,LocalDate.of(2026,9,21),3,2,"","",1));assertThrows(IllegalArgumentException.class,()->Protocol.query(c,LocalDate.of(2026,9,21),1,11,"","",1));}
    @Test public void realResponseIsParsed()throws Exception{var p=Protocol.page(new JSONObject(fixture("page.json")));assertEquals(108,p.total());assertEquals(15,p.rooms().size());assertEquals("A11-101",p.rooms().get(0).name());}
    @Test public void wrongCampusCannotLeakIntoResults()throws Exception{JSONObject j=new JSONObject(fixture("page.json"));j.getJSONArray("items").getJSONObject(0).put("xqh_id","108");assertThrows(java.io.IOException.class,()->Protocol.page(j));}
    @Test public void missingResultsAreNotInterpretedAsEmpty()throws Exception{assertThrows(JSONException.class,()->Protocol.page(new JSONObject("{}")));assertThrows(Protocol.AuthExpired.class,()->Protocol.document("<input name='yhm'>"));}
    @Test public void renamedCampusFailsClosed()throws Exception{assertThrows(java.io.IOException.class,()->Protocol.config(fixture("config.html").replace("科信","其他"),new JSONObject(fixture("metadata.json")),new JSONObject(fixture("term.json"))));}
    @Test public void rsaMatchesServerPkcs1Format()throws Exception{KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);KeyPair k=g.generateKeyPair();RSAPublicKey pub=(RSAPublicKey)k.getPublic();String encrypted=SchoolClient.encrypt("测试-password-123",Base64.getEncoder().encodeToString(pub.getModulus().toByteArray()),Base64.getEncoder().encodeToString(pub.getPublicExponent().toByteArray()));Cipher c=Cipher.getInstance("RSA/ECB/PKCS1Padding");c.init(Cipher.DECRYPT_MODE,k.getPrivate());assertEquals("测试-password-123",new String(c.doFinal(Base64.getDecoder().decode(encrypted)),StandardCharsets.UTF_8));}
    @Test public void futureOptionsDoNotOverrideCurrentSchoolTerm()throws Exception{assertTrue(fixture("config.html").contains("2031-2032"));assertEquals("2026",config().year());assertEquals("2026-2027-1",config().label());}
    @Test public void eachSearchReloadsAcademicYearAndTerm()throws Exception{
        var old=config();var updated=new Protocol.Config("2027","12","2027-2028-2",LocalDate.of(2028,2,28),1,old.weeks(),old.buildings(),old.types(),old.periods());
        List<Protocol.Config> seen=new ArrayList<>();
        SchoolClient client=new SchoolClient(){int loads=0;@Override public Protocol.Config config(){return loads++==0?old:updated;}@Override public Result query(Protocol.Config c,LocalDate d,int f,int t,String b,String kind){seen.add(c);return new Result(List.of(),0);}};
        client.searchCurrent(LocalDate.of(2026,9,21),1,2,"","03");client.searchCurrent(LocalDate.of(2028,3,1),1,2,"","03");assertEquals(List.of(old,updated),seen);
    }
    @Test public void newTermDatesUseNewCalendar()throws Exception{var c=config();var next=new Protocol.Config("2027","12","2027-2028-2",LocalDate.of(2028,2,28),1,c.weeks(),c.buildings(),c.types(),c.periods());var m=Protocol.query(next,LocalDate.of(2028,3,6),1,2,"","03",1);assertEquals("2027",m.get("xnm"));assertEquals("12",m.get("xqm"));assertEquals("2",m.get("zcd"));assertThrows(IllegalArgumentException.class,()->Protocol.query(next,LocalDate.of(2026,9,21),1,2,"","03",1));}
}
