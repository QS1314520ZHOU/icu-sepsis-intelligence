package org.icu.sepsis.notes;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;import org.junit.jupiter.api.Test;import com.fasterxml.jackson.databind.ObjectMapper;
class ClinicalNoteCryptoTest {
  static ClinicalNoteProperties properties(){ClinicalNoteProperties p=new ClinicalNoteProperties();p.keysJson="{\"synthetic-v1\":\""+Base64.getEncoder().encodeToString(new byte[32])+"\"}";p.activeKeyId="synthetic-v1";p.fingerprintKeyId="synthetic-v1";p.syncEnabled=true;return p;}
  static ClinicalNoteSnapshot note(){ClinicalNoteSnapshot n=new ClinicalNoteSnapshot();n.id="note";n.patientId="p";n.encounterId="e";n.department="ICU";n.versionKey="version";return n;}
  @Test void randomNonceAndNewCryptoInstanceCanReadPersistedCiphertext(){var p=properties();var crypto=new ClinicalNoteCrypto(p,new ObjectMapper());var a=note();var b=note();crypto.encrypt(a,"合成病程正文");crypto.encrypt(b,"合成病程正文");
    assertNotEquals(a.encryptedBody,b.encryptedBody);assertEquals("合成病程正文",new ClinicalNoteCrypto(p,new ObjectMapper()).decrypt(a));assertFalse(a.encryptedBody.contains("合成病程正文"));}
  @Test void crossEncounterCiphertextAndTamperingFailWithoutExposingBody(){var crypto=new ClinicalNoteCrypto(properties(),new ObjectMapper());var n=note();crypto.encrypt(n,"敏感合成正文");n.encounterId="different";
    var error=assertThrows(IllegalStateException.class,()->crypto.decrypt(n));assertFalse(error.getMessage().contains("敏感合成正文"));n.encounterId="e";n.encryptedBody="tampered";assertThrows(IllegalStateException.class,()->crypto.decrypt(n));}
  @Test void invalidConfigurationDisablesOnlyNotes(){var p=properties();p.keysJson="invalid-key-json";assertFalse(new ClinicalNoteCrypto(p,new ObjectMapper()).ready());}
  @Test void versionFingerprintIsStableAndSensitiveToContent(){var crypto=new ClinicalNoteCrypto(properties(),new ObjectMapper());assertEquals(crypto.fingerprint("body"),crypto.fingerprint("body"));assertNotEquals(crypto.fingerprint("body"),crypto.fingerprint("revision"));}

  @Test void encryptionKeyRotationRetainsOldReadabilityAndStableDedupKey(){var p=properties();var old=new ClinicalNoteCrypto(p,new ObjectMapper());var note=note();old.encrypt(note,"合成历史内容");String hash=old.fingerprint("same-source");
    byte[] key2=new byte[32];Arrays.fill(key2,(byte)1);p.keysJson=p.keysJson.substring(0,p.keysJson.length()-1)+",\"synthetic-v2\":\""+Base64.getEncoder().encodeToString(key2)+"\"}";p.activeKeyId="synthetic-v2";
    var rotated=new ClinicalNoteCrypto(p,new ObjectMapper());assertEquals("合成历史内容",rotated.decrypt(note));assertEquals(hash,rotated.fingerprint("same-source"));var newer=note();rotated.encrypt(newer,"新内容");assertEquals("synthetic-v2",newer.keyId);
  }

  @Test void metadataChangesInvalidateNewEnvelope(){var crypto=new ClinicalNoteCrypto(properties(),new ObjectMapper());var n=note();n.availableAt=java.time.Instant.EPOCH;n.availableTimeConfirmed=true;crypto.encrypt(n,"body");n.availableAt=java.time.Instant.EPOCH.plusSeconds(1);assertThrows(IllegalStateException.class,()->crypto.decrypt(n));}
  @Test void legacyCiphertextRemainsReadableWithoutClaimingAuthenticatedTime()throws Exception{var n=note();n.keyId="synthetic-v1";byte[] nonce=new byte[12];var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(new byte[32],"AES"),new javax.crypto.spec.GCMParameterSpec(128,nonce));cipher.updateAAD(ClinicalNoteCrypto.aad(n));n.encryptedBody=Base64.getEncoder().encodeToString(cipher.doFinal("legacy".getBytes(java.nio.charset.StandardCharsets.UTF_8)));n.nonce=Base64.getEncoder().encodeToString(nonce);assertEquals("legacy",new ClinicalNoteCrypto(properties(),new ObjectMapper()).decrypt(n));assertNull(n.cryptoVersion);}

  @Test void goldenMetadataV2ArchiveRemainsReadableWithNewWriter(){var n=note();n.cryptoVersion=2;n.keyId="synthetic-v1";n.nonce="AAAAAAAAAAAAAAAA";n.encryptedBody="vd4uSSUFHwdkbqi2zpL5eQYBI7wFhkYRtsOW94BsZ+2McUrLU9zR+b/zk3A=";
    assertEquals("synthetic metadata v2 legacy",new ClinicalNoteCrypto(properties(),new ObjectMapper()).decrypt(n));
  }
}
