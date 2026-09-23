package cn.kexin.rooms;

import android.content.Context;
import android.security.keystore.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import org.json.JSONObject;

final class CredentialVault {
    private static final String ALIAS="kexin_rooms_credentials_v1";
    private final Context context;
    record Credentials(String user,String password) {}
    CredentialVault(Context context){this.context=context.getApplicationContext();}
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();
    }
    void save(String user,String password) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(new JSONObject().put("u",user).put("p",password).toString().getBytes(StandardCharsets.UTF_8));
        String data=Base64.getEncoder().encodeToString(cipher.getIV())+":"+Base64.getEncoder().encodeToString(encrypted);
        if(!context.getSharedPreferences("vault",0).edit().putString("data",data).commit())throw new java.io.IOException("无法保存登录信息。");
    }
    Credentials load() throws Exception {
        String data=context.getSharedPreferences("vault",0).getString("data",null);if(data==null)return null;
        String[] parts=data.split(":");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));
        JSONObject j=new JSONObject(new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8));return new Credentials(j.getString("u"),j.getString("p"));
    }
    void clear() throws Exception {
        if(!context.getSharedPreferences("vault",0).edit().clear().commit())throw new java.io.IOException("无法清除登录信息。");
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(ALIAS))store.deleteEntry(ALIAS);
    }
}
