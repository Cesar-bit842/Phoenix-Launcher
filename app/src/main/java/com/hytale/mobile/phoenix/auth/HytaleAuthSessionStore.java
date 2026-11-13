package com.hytale.mobile.phoenix.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class HytaleAuthSessionStore {
    static final String PREFERENCES_FILE = "hytale_auth_session";

    private static final String KEY_ALIAS = "phoenix_hytale_auth_session";
    private static final String SESSION_KEY = "encrypted_session";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private HytaleAuthSessionStore() {
    }

    static synchronized void save(Context context, HytaleAuthLibrary.AuthTokens tokens)
            throws IOException {
        try {
            JSONObject session = new JSONObject();
            session.put("access_token", tokens.getAccessToken());
            session.put("refresh_token", tokens.getRefreshToken());
            session.put("expires_at", tokens.getExpiresAtEpochMillis());

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(session.toString().getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);

            boolean saved = preferences(context).edit()
                    .putString(SESSION_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
                    .commit();
            if (!saved) {
                throw new IOException("Could not save the encrypted Hytale session");
            }
        } catch (GeneralSecurityException | JSONException exception) {
            throw new IOException("Could not encrypt the Hytale session", exception);
        }
    }

    static synchronized HytaleAuthLibrary.AuthTokens load(Context context) throws IOException {
        String encoded = preferences(context).getString(SESSION_KEY, null);
        if (encoded == null) {
            return null;
        }

        try {
            byte[] payload = Base64.decode(encoded, Base64.NO_WRAP);
            if (payload.length <= GCM_IV_LENGTH_BYTES) {
                throw new IOException("Saved Hytale session data is incomplete");
            }

            byte[] iv = Arrays.copyOfRange(payload, 0, GCM_IV_LENGTH_BYTES);
            byte[] encrypted = Arrays.copyOfRange(payload, GCM_IV_LENGTH_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getExistingKey(),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            JSONObject session = new JSONObject(
                    new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));

            String accessToken = session.optString("access_token", "");
            if (accessToken.isEmpty()) {
                throw new IOException("Saved Hytale session does not contain an access token");
            }
            String refreshToken = session.isNull("refresh_token")
                    ? null
                    : session.optString("refresh_token", null);
            long expiresAt = session.optLong("expires_at", 0);
            if (expiresAt <= 0) {
                throw new IOException("Saved Hytale session has an invalid expiration");
            }
            long remainingSeconds = Math.max(
                    0L,
                    (expiresAt - System.currentTimeMillis() + 999L) / 1000L);
            return new HytaleAuthLibrary.AuthTokens(
                    accessToken, refreshToken, remainingSeconds, expiresAt);
        } catch (GeneralSecurityException | JSONException | IllegalArgumentException exception) {
            throw new IOException("Could not decrypt the saved Hytale session", exception);
        }
    }

    static boolean exists(Context context) {
        return preferences(context).contains(SESSION_KEY);
    }

    static synchronized void clear(Context context) throws IOException {
        boolean removed = preferences(context).edit().remove(SESSION_KEY).commit();
        if (!removed) {
            throw new IOException("Could not remove the saved Hytale session");
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not remove the Hytale session encryption key", exception);
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE);
    }

    private static Key getOrCreateKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        Key existingKey = keyStore.getKey(KEY_ALIAS, null);
        if (existingKey != null) {
            return existingKey;
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build());
        SecretKey key = keyGenerator.generateKey();
        if (key == null) {
            throw new IOException("Android Keystore did not create an encryption key");
        }
        return key;
    }

    private static Key getExistingKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        Key key = keyStore.getKey(KEY_ALIAS, null);
        if (key == null) {
            throw new IOException("Hytale session encryption key is unavailable");
        }
        return key;
    }
}
