package cn.kexin.rooms;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import android.graphics.Bitmap;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;

/** Runs only in the isolated test APK. Never supplies fixtures to the production network client. */
public final class SmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private String fixture(String name)throws Exception{try(var in=getContext().getAssets().open(name);var out=new java.io.ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private String labels(View v){String s=v instanceof TextView?((TextView)v).getText().toString()+"\n":"";if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)s+=labels(g.getChildAt(i));return s;}
    private void require(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    @Override public void onStart(){Bundle report=new Bundle();try{
        CredentialVault vault=new CredentialVault(getTargetContext());vault.clear();
        vault.save("instrumentation-only","fixture-value");var stored=vault.load();
        require(stored.user().equals("instrumentation-only")&&stored.password().equals("fixture-value"),"Keystore roundtrip failed");
        String disk=getTargetContext().getSharedPreferences("vault",0).getString("data","");require(!disk.contains("fixture-value")&&!disk.contains("instrumentation-only"),"Credentials stored in plaintext");vault.clear();require(vault.load()==null,"Credential removal failed");
        Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(1500);waitForIdleSync();AtomicReference<String> text=new AtomicReference<>();runOnMainSync(()->text.set(labels(activity.getWindow().getDecorView())));
        require(text.get().contains("登录并查询"),"Native login UI missing");
        Protocol.Config config=Protocol.config(fixture("config.html"),new JSONObject(fixture("metadata.json")),new JSONObject(fixture("term.json")));
        AtomicReference<Throwable> failure=new AtomicReference<>();runOnMainSync(()->{try{var field=MainActivity.class.getDeclaredField("config");field.setAccessible(true);field.set(activity,config);var method=MainActivity.class.getDeclaredMethod("showQuery");method.setAccessible(true);method.invoke(activity);text.set(labels(activity.getWindow().getDecorView()));}catch(Throwable t){failure.set(t);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());require(text.get().contains("选择自习时段")&&text.get().contains("查询空教室"),"Native query UI missing");
        waitForIdleSync();Thread.sleep(300);Protocol.Page page=Protocol.page(new JSONObject(fixture("page.json")));runOnMainSync(()->{try{var method=MainActivity.class.getDeclaredMethod("render",java.util.List.class);method.setAccessible(true);method.invoke(activity,page.rooms());}catch(Throwable t){failure.set(t);}});if(failure.get()!=null)throw new AssertionError(failure.get());
        waitForIdleSync();Thread.sleep(400);try(var out=new FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),"compact-query.png"))){getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,out);}
        // Return to genuine signed-out screen; don't leave fixture configuration in the visible app.
        runOnMainSync(()->{try{var method=MainActivity.class.getDeclaredMethod("showLogin",String.class);method.setAccessible(true);method.invoke(activity,(Object)null);}catch(Exception e){failure.set(e);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
        report.putString("stream","PASS: Android Keystore encrypt/decrypt/delete; native login screen; native query controls using redacted fixture; no school login attempted.\n");finish(Activity.RESULT_OK,report);
    }catch(Throwable e){report.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,report);}}
}
