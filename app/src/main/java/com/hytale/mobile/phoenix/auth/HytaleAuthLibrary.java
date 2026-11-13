package com.hytale.mobile.phoenix.auth;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Android helper for Hytale's browser-based OAuth authorization-code flow with PKCE.
 *
 * The account password is entered only on Hytale's authorization page. Tokens are persisted
 * encrypted with an Android Keystore key after login and are never logged by this class. Use a
 * client ID and redirect URI that Hytale has authorized for your application.
 */
public final class HytaleAuthLibrary {
    private static final String DEFAULT_AUTHORIZATION_ENDPOINT =
            "https://oauth.accounts.hytale.com/oauth2/auth";
    private static final String DEFAULT_TOKEN_ENDPOINT =
            "https://oauth.accounts.hytale.com/oauth2/token";
    private static final String DEFAULT_REDIRECT_URI =
            "https://accounts.hytale.com/consent/client";
    private static final String DEFAULT_SCOPES = "openid offline auth:launcher";
    private static final String CALLBACK_PATH = "/authorization-callback";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int CALLBACK_TIMEOUT_MS = 15 * 60 * 1000;
    private static final int MAX_RESPONSE_CHARS = 1_000_000;

    private final URI authorizationEndpoint;
    private final URI tokenEndpoint;
    private final URI redirectUri;
    private final String clientId;
    private final String scopes;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean loginInProgress = new AtomicBoolean(false);
    private final AtomicBoolean loginCancelled = new AtomicBoolean(false);
    private final AtomicReference<ServerSocket> callbackServer = new AtomicReference<>();
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Creates a client using the Hytale OAuth endpoints observed in HyPrism.
     * The supplied client ID must be authorized by Hytale for your application.
     */
    public HytaleAuthLibrary(String clientId) {
        this(
                URI.create(DEFAULT_AUTHORIZATION_ENDPOINT),
                URI.create(DEFAULT_TOKEN_ENDPOINT),
                URI.create(DEFAULT_REDIRECT_URI),
                clientId,
                DEFAULT_SCOPES);
    }

    /**
     * Creates a client with explicit OAuth settings.
     */
    public HytaleAuthLibrary(
            URI authorizationEndpoint,
            URI tokenEndpoint,
            URI redirectUri,
            String clientId,
            String scopes) {
        this.authorizationEndpoint = requireHttps(authorizationEndpoint, "authorizationEndpoint");
        this.tokenEndpoint = requireHttps(tokenEndpoint, "tokenEndpoint");
        this.redirectUri = requireHttps(redirectUri, "redirectUri");
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalArgumentException("clientId must not be empty");
        }
        if (scopes == null || scopes.trim().isEmpty()) {
            throw new IllegalArgumentException("scopes must not be empty");
        }
        this.clientId = clientId.trim();
        this.scopes = scopes.trim();
    }

    /**
     * Opens Hytale authorization in the external browser, saves the resulting OAuth tokens
     * encrypted on the device, and returns them. Network and storage work runs off the UI thread;
     * callback methods run on Android's main thread.
     */
    public void login(Context context, Callback<AuthTokens> callback) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(callback, "callback");
        if (!loginInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("An authentication flow is already in progress");
        }

        loginCancelled.set(false);
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                AuthTokens tokens = performLogin(appContext);
                HytaleAuthSessionStore.save(appContext, tokens);
                postSuccess(callback, tokens);
            } catch (Exception exception) {
                postError(callback, exception);
            } finally {
                loginInProgress.set(false);
                callbackServer.set(null);
            }
        }, "hytale-oauth-login").start();
    }

    /**
     * Cancels the active login attempt, if one exists.
     */
    public void cancelLogin() {
        loginCancelled.set(true);
        ServerSocket server = callbackServer.getAndSet(null);
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Closing a local callback listener is best-effort during cancellation.
            }
        }
    }

    /**
     * Exchanges a refresh token for a new access token on a background thread.
     * The caller remains responsible for securely storing any returned tokens.
     */
    public void refresh(String refreshToken, Callback<AuthTokens> callback) {
        Objects.requireNonNull(callback, "callback");
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            throw new IllegalArgumentException("refreshToken must not be empty");
        }
        new Thread(() -> {
            try {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("grant_type", "refresh_token");
                form.put("refresh_token", refreshToken);
                form.put("client_id", clientId);
                postSuccess(callback, exchangeTokens(form, refreshToken));
            } catch (Exception exception) {
                postError(callback, exception);
            }
        }, "hytale-oauth-refresh").start();
    }

    /**
     * Restores the encrypted session saved by {@link #login(Context, Callback)}.
     * If its access token expires within one minute, the refresh token is exchanged and the
     * updated session is saved before the callback succeeds. Callbacks run on Android's main
     * thread. If no session is saved, or it cannot be restored, {@code onError} is called.
     */
    public void restoreSession(Context context, Callback<AuthTokens> callback) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(callback, "callback");
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                AuthTokens savedTokens = HytaleAuthSessionStore.load(appContext);
                if (savedTokens == null) {
                    throw new IOException("No saved Hytale session was found");
                }

                if (savedTokens.getExpiresAtEpochMillis() > System.currentTimeMillis() + 60_000L) {
                    postSuccess(callback, savedTokens);
                    return;
                }
                if (savedTokens.getRefreshToken() == null
                        || savedTokens.getRefreshToken().trim().isEmpty()) {
                    throw new IOException("The saved Hytale session has no refresh token");
                }

                Map<String, String> form = new LinkedHashMap<>();
                form.put("grant_type", "refresh_token");
                form.put("refresh_token", savedTokens.getRefreshToken());
                form.put("client_id", clientId);
                AuthTokens refreshedTokens = exchangeTokens(form, savedTokens.getRefreshToken());
                HytaleAuthSessionStore.save(appContext, refreshedTokens);
                postSuccess(callback, refreshedTokens);
            } catch (Exception exception) {
                postError(callback, exception);
            }
        }, "hytale-oauth-restore").start();
    }

    /**
     * Returns whether encrypted session data is present. This does not decrypt or validate it.
     */
    public boolean hasSavedSession(Context context) {
        Objects.requireNonNull(context, "context");
        return HytaleAuthSessionStore.exists(context.getApplicationContext());
    }

    /**
     * Removes the saved session and its encryption key from this app's Android Keystore.
     */
    public void logout(Context context) throws IOException {
        Objects.requireNonNull(context, "context");
        HytaleAuthSessionStore.clear(context.getApplicationContext());
    }

    private AuthTokens performLogin(Context context) throws Exception {
        checkCancelled();
        String verifier = generateVerifier();
        String challenge = base64Url(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        String callbackState = randomState();

        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(CALLBACK_TIMEOUT_MS);
            if (loginCancelled.get()) {
                throw new IOException("Authentication was cancelled");
            }
            callbackServer.set(server);
            checkCancelled();

            String state = encodeCallbackState(callbackState, server.getLocalPort());
            URI authorizationUri = buildAuthorizationUri(challenge, state);
            openBrowser(context, authorizationUri);

            CallbackResult result = receiveCallback(server, callbackState);
            checkCancelled();
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", result.code);
            form.put("redirect_uri", redirectUri.toString());
            form.put("client_id", clientId);
            form.put("code_verifier", verifier);
            return exchangeTokens(form, null);
        } finally {
            callbackServer.set(null);
        }
    }

    private URI buildAuthorizationUri(String challenge, String state) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("access_type", "offline");
        query.put("client_id", clientId);
        query.put("code_challenge", challenge);
        query.put("code_challenge_method", "S256");
        query.put("redirect_uri", redirectUri.toString());
        query.put("response_type", "code");
        query.put("scope", scopes);
        query.put("state", state);

        StringBuilder uri = new StringBuilder(authorizationEndpoint.toString());
        uri.append(authorizationEndpoint.getRawQuery() == null ? '?' : '&');
        appendForm(uri, query);
        return URI.create(uri.toString());
    }

    private void openBrowser(Context context, URI authorizationUri) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Exception> startError = new AtomicReference<>();
        mainHandler.post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUri.toString()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception exception) {
                startError.set(exception);
            } finally {
                started.countDown();
            }
        });

        if (!started.await(30, TimeUnit.SECONDS)) {
            throw new IOException("Timed out while opening the Hytale sign-in page");
        }
        if (startError.get() != null) {
            throw new IOException("Could not open the external browser", startError.get());
        }
    }

    private CallbackResult receiveCallback(ServerSocket server, String expectedState) throws Exception {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(10_000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII.name()));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.length() > 16_384) {
                writeCallbackResponse(socket, 400, false);
                throw new IOException("Invalid OAuth callback request");
            }

            int headerBytes = 0;
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                headerBytes += header.length();
                if (headerBytes > 32_768) {
                    writeCallbackResponse(socket, 400, false);
                    throw new IOException("OAuth callback headers are too large");
                }
            }

            String[] requestParts = requestLine.split(" ", 3);
            if (requestParts.length != 3 || !"GET".equals(requestParts[0])) {
                writeCallbackResponse(socket, 400, false);
                throw new IOException("Unsupported OAuth callback request");
            }

            URI requestUri;
            try {
                requestUri = URI.create("http://127.0.0.1" + requestParts[1]);
            } catch (IllegalArgumentException exception) {
                writeCallbackResponse(socket, 400, false);
                throw new IOException("Malformed OAuth callback URL", exception);
            }
            if (!CALLBACK_PATH.equals(requestUri.getPath())) {
                writeCallbackResponse(socket, 404, false);
                throw new IOException("Unexpected OAuth callback path");
            }

            Map<String, String> query = parseQuery(requestUri.getRawQuery());
            String returnedState = query.get("state");
            if (returnedState == null || !MessageDigest.isEqual(
                    expectedState.getBytes(StandardCharsets.UTF_8),
                    returnedState.getBytes(StandardCharsets.UTF_8))) {
                writeCallbackResponse(socket, 400, false);
                throw new IOException("OAuth state validation failed");
            }

            String error = query.get("error");
            if (error != null && !error.isEmpty()) {
                writeCallbackResponse(socket, 400, false);
                throw new IOException("Hytale authorization failed: " + error);
            }
            String code = query.get("code");
            if (code == null || code.isEmpty()) {
                writeCallbackResponse(socket, 400, false);
                throw new IOException("OAuth callback did not contain an authorization code");
            }

            writeCallbackResponse(socket, 200, true);
            return new CallbackResult(code);
        } catch (SocketTimeoutException exception) {
            throw new IOException("Timed out waiting for Hytale authorization", exception);
        }
    }

    private AuthTokens exchangeTokens(Map<String, String> form, String previousRefreshToken)
            throws IOException, JSONException {
        HttpURLConnection connection = null;
        try {
            URL url = tokenEndpoint.toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

            try (OutputStream output = connection.getOutputStream();
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(output, StandardCharsets.UTF_8.name()))) {
                appendForm(writer, form);
            }

            int status = connection.getResponseCode();
            InputStream responseStream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String body = responseStream == null ? "" : readLimited(responseStream);
            if (status < 200 || status >= 300) {
                throw new IOException("Hytale token endpoint returned HTTP " + status);
            }

            JSONObject json = new JSONObject(body);
            String accessToken = json.optString("access_token", "");
            if (accessToken.isEmpty()) {
                throw new IOException("Hytale token response did not contain an access token");
            }
            String refreshToken = json.isNull("refresh_token")
                    ? previousRefreshToken
                    : json.optString("refresh_token", previousRefreshToken);
            long expiresIn = json.optLong("expires_in", 0);
            if (expiresIn <= 0) {
                throw new IOException("Hytale token response contained an invalid expiration");
            }
            return new AuthTokens(accessToken, refreshToken, expiresIn);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private URI requireHttps(URI uri, String name) {
        Objects.requireNonNull(uri, name);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(name + " must be an absolute HTTPS URI without a fragment");
        }
        return uri;
    }

    private static String encodeCallbackState(String callbackState, int port) throws JSONException {
        JSONObject state = new JSONObject();
        state.put("state", callbackState);
        state.put("port", Integer.toString(port));
        return Base64.getEncoder().encodeToString(state.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String randomState() {
        char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
        char[] result = new char[26];
        for (int index = 0; index < result.length; index++) {
            result[index] = alphabet[secureRandom.nextInt(alphabet.length)];
        }
        return new String(result);
    }

    private static String generateVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return base64Url(bytes);
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    private static String base64Url(byte[] input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input);
    }

    private void checkCancelled() throws IOException {
        if (loginCancelled.get()) {
            throw new IOException("Authentication was cancelled");
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        try {
            for (String pair : rawQuery.split("&")) {
                int separator = pair.indexOf('=');
                String key = separator < 0 ? pair : pair.substring(0, separator);
                String value = separator < 0 ? "" : pair.substring(separator + 1);
                result.put(
                        URLDecoder.decode(key, "UTF-8"),
                        URLDecoder.decode(value, "UTF-8"));
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Malformed OAuth callback parameters", exception);
        }
    }

    private static void writeCallbackResponse(Socket socket, int status, boolean success) throws IOException {
        String title = success ? "Authorization complete" : "Authorization failed";
        String message = success
                ? "You can close this window and return to the app."
                : "Return to the app and try signing in again.";
        byte[] body = ("<!doctype html><html><head><meta charset=\"utf-8\"><title>" + title
                + "</title></head><body><main><h1>" + title + "</h1><p>" + message
                + "</p></main></body></html>").getBytes(StandardCharsets.UTF_8);
        String statusText = status == 200 ? "OK" : status == 404 ? "Not Found" : "Bad Request";
        OutputStream output = socket.getOutputStream();
        output.write(("HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static String readLimited(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_CHARS) {
                    throw new IOException("Hytale token response is too large");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void appendForm(StringBuilder output, Map<String, String> values) {
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                output.append('&');
            }
            first = false;
            output.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
    }

    private static void appendForm(BufferedWriter output, Map<String, String> values) throws IOException {
        StringBuilder form = new StringBuilder();
        appendForm(form, values);
        output.write(form.toString());
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException exception) {
            throw new IllegalStateException("UTF-8 is not available", exception);
        }
    }

    private static void postSuccess(Callback<AuthTokens> callback, AuthTokens tokens) {
        new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(tokens));
    }

    private static void postError(Callback<AuthTokens> callback, Exception exception) {
        new Handler(Looper.getMainLooper()).post(() -> callback.onError(exception));
    }

    public interface Callback<T> {
        void onSuccess(T result);

        void onError(Exception error);
    }

    /**
     * OAuth tokens returned by Hytale. This object contains sensitive credentials.
     */
    public static final class AuthTokens {
        private final String accessToken;
        private final String refreshToken;
        private final long expiresInSeconds;
        private final long expiresAtEpochMillis;

        private AuthTokens(String accessToken, String refreshToken, long expiresInSeconds) {
            this(
                    accessToken,
                    refreshToken,
                    expiresInSeconds,
                    System.currentTimeMillis() + expiresInSeconds * 1000L);
        }

        AuthTokens(
                String accessToken,
                String refreshToken,
                long expiresInSeconds,
                long expiresAtEpochMillis) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresInSeconds = expiresInSeconds;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public String getRefreshToken() {
            return refreshToken;
        }

        public long getExpiresInSeconds() {
            return expiresInSeconds;
        }

        /**
         * Returns the access token expiration time as milliseconds since the Unix epoch.
         */
        public long getExpiresAtEpochMillis() {
            return expiresAtEpochMillis;
        }
    }

    private static final class CallbackResult {
        private final String code;

        private CallbackResult(String code) {
            this.code = code;
        }
    }
}
