package cn.kexin.rooms;

import android.app.*;
import android.os.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int GREEN=0xff146c55, INK=0xff203c32, MUTED=0xff63746c, BG=0xfff5f7f3;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final SchoolClient client=new SchoolClient();
    private CredentialVault vault;
    private CredentialVault.Credentials credentials;
    private Protocol.Config config;
    private LinearLayout root, content, results, filters;
    private TextView status, summary, dateText;
    private ProgressBar progress;
    private Button query,editFilters;
    private Spinner start,end,building,type;
    private LocalDate date=LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private boolean followToday=true;
    private boolean busy=false, ready=false, binding=false;
    private long lastFetched=0;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);vault=new CredentialVault(this);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);root.requestApplyInsets();showShell();
        busy=true;worker.execute(()->{try{var savedCredentials=vault.load();runOnUiThread(()->{busy=false;if(savedCredentials==null)showLogin(null);else{credentials=savedCredentials;authenticate(null);}});}catch(Exception e){runOnUiThread(()->{busy=false;showLogin("本机登录信息无法解密，请重新输入。");});}});
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable s=new GradientDrawable();s.setColor(color);s.setCornerRadius(dp(radius));return s;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(3),0,dp(3));return t;}
    private LinearLayout column(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);return c;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(14),dp(10),dp(14),dp(10));c.setBackground(shape(Color.WHITE,14));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,dp(3));c.setLayoutParams(p);return c;}
    private Button button(String label,boolean primary){Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setAllCaps(false);b.setMinHeight(dp(44));b.setMinWidth(0);b.setPadding(dp(8),0,dp(8),0);b.setTextColor(primary?Color.WHITE:GREEN);b.setBackgroundTintList(ColorStateList.valueOf(primary?GREEN:0xffe6eee7));return b;}
    private void showShell(){
        root.removeAllViews();ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,-1));content=column();content.setPadding(dp(16),dp(10),dp(16),dp(20));scroll.addView(content);
        TextView brand=text("KEXIN  /  STUDY SPACE",9,GREEN);brand.setLetterSpacing(.12f);content.addView(brand);
        TextView title=text("找一间空教室",24,INK);title.setTypeface(null,Typeface.BOLD);content.addView(title);
        content.addView(text("科信校区 · 河北工程大学",13,MUTED));
        status=text("正在连接教务系统…",12,MUTED);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setIndeterminateTintList(ColorStateList.valueOf(GREEN));content.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));
    }
    private void showLogin(String error){
        ready=false;config=null;credentials=null;showShell();progress.setVisibility(View.GONE);status.setText(error==null?"首次登录后，下次打开自动连接。":error);
        LinearLayout c=card();c.addView(text("连接你的教务账号",20,INK));
        EditText user=new EditText(this);user.setHint("学号 / 教务用户名");user.setSingleLine(true);user.setInputType(InputType.TYPE_CLASS_TEXT);user.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);c.addView(user);
        EditText password=new EditText(this);password.setHint("教务密码");password.setSingleLine(true);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);c.addView(password);
        Button sign=button("登录并查询",true);c.addView(sign);sign.setOnClickListener(v->{if(busy)return;String u=user.getText().toString().trim(),p=password.getText().toString();if(u.isEmpty()||p.isEmpty()){status.setText("请输入账号和密码。");return;}credentials=new CredentialVault.Credentials(u,p);password.setText("");authenticate(null);});
        c.addView(text("账号密码仅加密保存在本机；连接学校教务系统，无中转服务器。",13,MUTED));content.addView(c);
        content.addView(text("仅提供空闲教室查询。若学校要求验证码，将由你手动输入。",13,MUTED));
    }
    private void setBusy(String message){busy=true;status.setText(message);progress.setVisibility(View.VISIBLE);if(query!=null)query.setEnabled(false);if(filters!=null)enableTree(filters,false);}
    private void endBusy(){busy=false;progress.setVisibility(View.GONE);if(query!=null)query.setEnabled(true);if(filters!=null)enableTree(filters,true);}
    private void enableTree(View v,boolean enabled){v.setEnabled(enabled);if(v instanceof android.view.ViewGroup g)for(int i=0;i<g.getChildCount();i++)enableTree(g.getChildAt(i),enabled);}
    private void authenticate(String code){
        if(credentials==null)return;setBusy("正在登录并读取科信校区配置…");
        final var auth=credentials;
        worker.execute(()->{try{client.login(auth.user(),auth.password(),code);Protocol.Config c=client.config();vault.save(auth.user(),auth.password());runOnUiThread(()->{if(isDestroyed())return;config=c;endBusy();showQuery();fetchRooms();});}
            catch(SchoolClient.CaptchaRequired e){loadCaptcha();}
            catch(Exception e){runOnUiThread(()->{if(isDestroyed())return;endBusy();showLogin(friendly(e));});}});
    }
    private String friendly(Exception e){if(e instanceof java.net.SocketTimeoutException)return "教务系统连接超时，请检查网络后重试。";if(e instanceof java.net.UnknownHostException)return "无法连接教务系统，请检查网络或校园网。";if(e instanceof javax.net.ssl.SSLException)return "学校 HTTPS 连接验证失败，请检查手机时间或稍后重试。";return e.getMessage()==null?"查询未完成，请稍后重试。":e.getMessage();}
    private void loadCaptcha(){
        try{byte[] bytes=client.captcha();runOnUiThread(()->{if(isDestroyed())return;endBusy();LinearLayout c=column();c.setPadding(dp(24),dp(12),dp(24),0);ImageView image=new ImageView(this);image.setImageBitmap(BitmapFactory.decodeByteArray(bytes,0,bytes.length));image.setContentDescription("学校登录验证码");c.addView(image,new LinearLayout.LayoutParams(-1,dp(80)));EditText code=new EditText(this);code.setSingleLine(true);code.setHint("输入图片验证码");c.addView(code);
            new AlertDialog.Builder(this).setTitle("学校要求验证码").setView(c).setPositiveButton("继续登录",(d,w)->authenticate(code.getText().toString().trim())).setNeutralButton("换一张",(d,w)->{setBusy("读取验证码…");worker.execute(this::loadCaptcha);}).setNegativeButton("取消",(d,w)->showLogin("已取消验证码登录。")).setOnCancelListener(d->showLogin("已取消验证码登录。")).show();});}
        catch(Exception e){runOnUiThread(()->{endBusy();showLogin(friendly(e));});}
    }
    private Spinner compactSpinner(LinearLayout parent,String label,List<?> values){LinearLayout cell=column();cell.setPadding(0,0,dp(8),0);cell.addView(text(label,10,MUTED));Spinner s=new Spinner(this);ArrayAdapter<Object> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new ArrayList<>(values));a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);cell.addView(s,new LinearLayout.LayoutParams(-1,dp(42)));parent.addView(cell,new LinearLayout.LayoutParams(0,-2,1));return s;}
    private void showQuery(){
        showShell();progress.setVisibility(View.GONE);status.setText("已连接 · 东校区（科信学院）");binding=true;
        filters=card();filters.addView(text("选择自习时段",16,INK));
        LinearLayout dateLine=new LinearLayout(this);dateLine.setGravity(Gravity.CENTER_VERTICAL);dateText=text("",13,INK);updateDateLabel();dateLine.addView(dateText,new LinearLayout.LayoutParams(0,-2,1));Button pick=button("选日期",false),today=button("今天",false);dateLine.addView(pick,new LinearLayout.LayoutParams(dp(72),dp(44)));dateLine.addView(today,new LinearLayout.LayoutParams(dp(62),dp(44)));filters.addView(dateLine);
        pick.setOnClickListener(v->{DatePickerDialog dialog=new DatePickerDialog(this,(view,y,m,d)->{date=LocalDate.of(y,m+1,d);followToday=false;updateDateLabel();invalidateResults();},date.getYear(),date.getMonthValue()-1,date.getDayOfMonth());dialog.show();});
        today.setOnClickListener(v->{followToday=true;date=LocalDate.now(ZoneId.of("Asia/Shanghai"));updateDateLabel();invalidateResults();});
        LinearLayout periodRow=new LinearLayout(this);start=compactSpinner(periodRow,"开始节次",config.periods());end=compactSpinner(periodRow,"结束节次",config.periods());filters.addView(periodRow);end.setSelection(Math.min(1,config.periods().size()-1));
        LinearLayout placeRow=new LinearLayout(this);building=compactSpinner(placeRow,"教学楼",config.buildings());type=compactSpinner(placeRow,"场地类别",config.types());filters.addView(placeRow);
        // Match the school's default classroom category, not playgrounds and virtual classrooms.
        for(int i=0;i<config.types().size();i++)if(config.types().get(i).id().equals("03"))type.setSelection(i);
        content.addView(filters);query=button("查询空教室",true);content.addView(query,new LinearLayout.LayoutParams(-1,dp(48)));query.setOnClickListener(v->fetchRooms());
        summary=text("",11,MUTED);content.addView(summary);editFilters=button("修改查询条件",false);editFilters.setVisibility(View.GONE);content.addView(editFilters,new LinearLayout.LayoutParams(-1,dp(42)));editFilters.setOnClickListener(v->{filters.setVisibility(View.VISIBLE);query.setVisibility(View.VISIBLE);editFilters.setVisibility(View.GONE);});results=column();content.addView(results);
        content.addView(text("结果来自学校排课及占用记录，现场开放情况以学校管理为准。",10,MUTED));
        Button account=button("账号与隐私",false);content.addView(account);account.setOnClickListener(v->{if(busy)return;new AlertDialog.Builder(this).setTitle("账号与隐私").setMessage("密码由 Android Keystore 加密保存在本机，不上传到第三方。仅查询空教室，不含选课、预约或成绩功能。退出会清除本机账号密码和会话。\n\n非学校官方应用 · 1.0.0").setPositiveButton("关闭",null).setNegativeButton("退出并清除账号",(d,w)->logout()).show();});
        AdapterView.OnItemSelectedListener listener=new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(!binding)invalidateResults();}public void onNothingSelected(AdapterView<?> p){}};
        start.setOnItemSelectedListener(listener);end.setOnItemSelectedListener(listener);building.setOnItemSelectedListener(listener);type.setOnItemSelectedListener(listener);
        content.post(()->binding=false);ready=true;
    }
    private void updateDateLabel(){dateText.setText(date.format(DateTimeFormatter.ofPattern("M月d日  EEEE",Locale.CHINA))+"\n"+config.label());}
    private void invalidateResults(){if(busy)return;results.removeAllViews();summary.setText("条件已更新，请点击查询。");editFilters.setVisibility(View.GONE);lastFetched=0;}
    private void fetchRooms(){
        if(busy||config==null)return;
        if(followToday){date=LocalDate.now(ZoneId.of("Asia/Shanghai"));updateDateLabel();}
        final LocalDate selectedDate=date;
        final int from=((Protocol.Period)start.getSelectedItem()).number(),to=((Protocol.Period)end.getSelectedItem()).number();
        final String b=((Protocol.Option)building.getSelectedItem()).id(),t=((Protocol.Option)type.getSelectedItem()).id();
        if(from>to){results.removeAllViews();status.setText("结束节次不能早于开始节次。");summary.setText("尚未查询");return;}
        results.removeAllViews();summary.setText("正在重新核对学校当前学年、学期和校历…");lastFetched=0;setBusy("正在实时查询科信校区…");
        worker.execute(()->{try{
            SchoolClient.Search search;
            try{search=client.searchCurrent(selectedDate,from,to,b,t);}catch(Protocol.AuthExpired e){if(credentials==null)throw e;client.login(credentials.user(),credentials.password(),null);search=client.searchCurrent(selectedDate,from,to,b,t);}
            final var completed=search;runOnUiThread(()->{if(isDestroyed())return;
                if(!config.equals(completed.config())){config=completed.config();showQuery();for(int i=0;i<config.periods().size();i++){if(config.periods().get(i).number()==from)start.setSelection(i);if(config.periods().get(i).number()==to)end.setSelection(i);}for(int i=0;i<config.buildings().size();i++)if(config.buildings().get(i).id().equals(b))building.setSelection(i);for(int i=0;i<config.types().size();i++)if(config.types().get(i).id().equals(t))type.setSelection(i);}
                endBusy();updateDateLabel();var result=completed.result();lastFetched=result.fetchedAt();status.setText("查询完成 · 科信校区 · 当前学期已核对");summary.setText(selectedDate+" · 第"+from+"—"+to+"节\n学校返回 "+result.rooms().size()+" 个空闲场地 · "+Instant.ofEpochMilli(lastFetched).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("HH:mm:ss"))+" 更新");render(result.rooms());});
        }catch(SchoolClient.CaptchaRequired e){loadCaptcha();}catch(Exception e){runOnUiThread(()->{if(isDestroyed())return;endBusy();status.setText(friendly(e));summary.setText("查询失败，未展示旧结果。点击查询可重试。");});}});
    }
    private void render(List<Protocol.Room> rooms){results.removeAllViews();if(rooms.isEmpty()){LinearLayout empty=card();empty.addView(text("这个时段没有符合条件的空教室",16,INK));empty.addView(text("试试其他节次、教学楼或场地类别。",12,MUTED));results.addView(empty);return;}filters.setVisibility(View.GONE);query.setVisibility(View.GONE);editFilters.setVisibility(View.VISIBLE);for(var room:rooms){LinearLayout c=card();c.setOrientation(LinearLayout.HORIZONTAL);c.setGravity(Gravity.CENTER_VERTICAL);LinearLayout details=column();TextView name=text(room.name(),18,INK);name.setTypeface(null,Typeface.BOLD);name.setSingleLine(true);details.addView(name);TextView meta=text(room.building()+" · "+room.type(),11,MUTED);meta.setSingleLine(true);details.addView(meta);c.addView(details,new LinearLayout.LayoutParams(0,-2,1));LinearLayout facts=column();facts.setGravity(Gravity.END);TextView seats=text(room.seats()+" 座",13,GREEN);seats.setGravity(Gravity.END);seats.setTypeface(null,Typeface.BOLD);facts.addView(seats);TextView bookable=text("可借用 "+room.bookable(),10,MUTED);bookable.setGravity(Gravity.END);facts.addView(bookable);c.addView(facts,new LinearLayout.LayoutParams(-2,-2));results.addView(c);}}
    private void logout(){setBusy("清除本机账号…");worker.execute(()->{try{vault.clear();client.clear();runOnUiThread(()->{endBusy();showLogin("已清除本机账号和会话。");});}catch(Exception e){runOnUiThread(()->{endBusy();status.setText(friendly(e));});}});}
    @Override public void onResume(){super.onResume();if(ready&&!busy){if(followToday){date=LocalDate.now(ZoneId.of("Asia/Shanghai"));updateDateLabel();}fetchRooms();}}
    @Override public void onPause(){super.onPause();if(ready&&!busy&&lastFetched>0)status.setText("查询快照 · 返回后将检查是否需要刷新");}
    @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
