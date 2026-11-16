package dev.pocketai;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecretStore {
  private static final String ALIAS = "pocketai.api.v1";

  SecretStore() {}

  private static SecretKey key() throws GeneralSecurityException, IOException {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias(ALIAS)) {
      KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      generator.generateKey();
    }
    return (SecretKey) store.getKey(ALIAS, null);
  }

  static void save(Context context, String value) throws GeneralSecurityException, IOException {
    save(context, "api", value);
  }

  static void save(Context context, String slot, String value)
      throws GeneralSecurityException, IOException {
    if (value.isEmpty()) {
      if (!context.getSharedPreferences("secrets", 0).edit().remove(slot).commit()) {
        throw new IOException("Не удалось удалить сохранённый ключ.");
      }
      return;
    }
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(1, key());
    String encrypted =
        Base64.encodeToString(cipher.getIV(), 2)
            + ":"
            + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), 2);
    if (!context.getSharedPreferences("secrets", 0).edit().putString(slot, encrypted).commit()) {
      throw new IOException("Не удалось сохранить ключ.");
    }
  }

  static String load(Context context) throws GeneralSecurityException, IOException {
    return load(context, "api");
  }

  static String load(Context context, String slot) throws GeneralSecurityException, IOException {
    String encrypted = context.getSharedPreferences("secrets", 0).getString(slot, "");
    if (encrypted.isEmpty()) {
      return "";
    }
    try {
      String[] parts = encrypted.split(":", -1);
      if (parts.length != 2) {
        throw new GeneralSecurityException("Повреждён сохранённый ключ.");
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(2, key(), new GCMParameterSpec(128, Base64.decode(parts[0], 2)));
      return new String(cipher.doFinal(Base64.decode(parts[1], 2)), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new GeneralSecurityException("Повреждён сохранённый ключ.");
    }
  }
}
