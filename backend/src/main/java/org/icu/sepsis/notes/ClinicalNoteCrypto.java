package org.icu.sepsis.notes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;import java.security.SecureRandom;import java.util.*;
import javax.crypto.*;import javax.crypto.spec.*;
import org.springframework.stereotype.Component;
@Component
public class ClinicalNoteCrypto {
  private final Map<String,byte[]> keys=new HashMap<>();private final ClinicalNoteProperties props;
  private final SecureRandom random=new SecureRandom();
  public ClinicalNoteCrypto(ClinicalNoteProperties props,ObjectMapper json){this.props=props;
    try {var root=json.readTree(props.keysJson);if(!root.isObject())throw new IllegalArgumentException();
      var fields=root.fields();while(fields.hasNext()){var field=fields.next();byte[] key=Base64.getDecoder().decode(field.getValue().asText());
        if(!field.getKey().matches("[A-Za-z0-9_-]{1,64}")||key.length!=32)throw new IllegalArgumentException();keys.put(field.getKey(),key);}}
    catch(Exception ex){keys.clear(); /* Invalid note keys disable this feature, never the clinical rule engine. */}
  }
  public boolean ready(){return keys.containsKey(props.activeKeyId)&&keys.containsKey(props.fingerprintKeyId);}
  public String fingerprint(String input){try{Mac derive=Mac.getInstance("HmacSHA256");derive.init(new SecretKeySpec(keys.get(props.fingerprintKeyId),"HmacSHA256"));
      byte[] subkey=derive.doFinal("icu-note-fingerprint-v1".getBytes(StandardCharsets.UTF_8));Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(subkey,"HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));}
    catch(Exception ex){throw new IllegalStateException("病程快照指纹密钥暂不可用");}}
  static byte[] aad(ClinicalNoteSnapshot n){
    if(n.cryptoVersion==null||n.cryptoVersion==1)return (n.department+"|"+n.patientId+"|"+n.encounterId+"|"+n.id+"|"+n.versionKey).getBytes(StandardCharsets.UTF_8);
    if(n.cryptoVersion!=2&&n.cryptoVersion!=3)throw new IllegalArgumentException("Unsupported note envelope");
    List<Object> fields=Arrays.asList(n.cryptoVersion,n.department,n.patientId,n.encounterId,n.id,n.versionKey,n.documentKey,n.source,n.documentType,n.timeMeaning,
        n.documentedAt,n.availableAt,n.syncedAt,n.identityConfirmed,n.availableTimeConfirmed,n.documentTypeConfirmed,n.versionOrderConfirmed,n.keyId,n.fingerprintKeyId);
    if(n.cryptoVersion==3){fields=new ArrayList<>(fields);fields.add(n.timePrecision);fields.add(n.timeConflict);}
    StringBuilder encoded=new StringBuilder();for(Object field:fields){String value=field instanceof java.time.Instant?Long.toString(((java.time.Instant)field).toEpochMilli()):Objects.toString(field,"null");encoded.append(value.length()).append(':').append(value);}
    return encoded.toString().getBytes(StandardCharsets.UTF_8);
  }
  public void encrypt(ClinicalNoteSnapshot note,String plaintext){try{note.cryptoVersion=3;note.keyId=props.activeKeyId;note.fingerprintKeyId=props.fingerprintKeyId;byte[] nonce=new byte[12];random.nextBytes(nonce);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(keys.get(props.activeKeyId),"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(note));
      note.encryptedBody=Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));note.nonce=Base64.getEncoder().encodeToString(nonce);
      note.keyId=props.activeKeyId;note.fingerprintKeyId=props.fingerprintKeyId;
    }catch(Exception ex){throw new IllegalStateException("病程快照加密失败");}}
  public String decrypt(ClinicalNoteSnapshot note){try{byte[] key=keys.get(note.keyId);if(key==null)throw new IllegalArgumentException();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(note.nonce)));cipher.updateAAD(aad(note));
      return new String(cipher.doFinal(Base64.getDecoder().decode(note.encryptedBody)),StandardCharsets.UTF_8);
    }catch(Exception ex){throw new IllegalStateException("病程快照无法解密；请核对授权及历史密钥配置");}}
}
