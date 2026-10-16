/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.glavo.uuid.UUIDs;
import org.jackhuang.hmcl.auth.yggdrasil.GameProfile;
import org.jackhuang.hmcl.auth.yggdrasil.YggdrasilSession;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.upgrade.FreeCoreBootstrap;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// Exchanges an expiring device request with S256 proof; the browser never receives the verifier or game token.
@NotNullByDefault
final class FreeCoreBrowserAuthorization {
    /// Cookie-free client which must not forward secrets across redirects.
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    /// Provider origin, injectable for local protocol tests.
    private final URI origin;
    /// Per-request proof secret retained only in launcher memory.
    private final String verifier = randomSecret();
    /// Binds the response to this exact authorization attempt.
    private final String state = randomSecret();
    /// Client token checked against the returned Yggdrasil session.
    private final String clientToken = UUIDs.toCompactString(UUID.randomUUID());
    /// Opaque device request returned by the server.
    private @Nullable String code;

    /// Creates a coordinator for a trusted provider origin.
    FreeCoreBrowserAuthorization(URI origin) { this.origin = origin; }

    /// Starts authorization and rejects URLs outside the intended provider and route.
    URI start() throws IOException, InterruptedException {
        JsonObject request = new JsonObject();
        request.addProperty("challenge", challenge(verifier));
        request.addProperty("state", state);
        request.addProperty("clientToken", clientToken);
        HttpResponse<String> response = post("start", request);
        if (response.statusCode() == 404)
            throw new UnsupportedOperationException("皮肤站未提供桌面启动器授权接口");
        requireSuccess(response);
        JsonObject result = parse(response.body());
        String receivedCode = required(result, "code");
        URI url = URI.create(required(result, "authorizationUrl"));
        if (!receivedCode.matches("[a-f0-9]{64}") || !origin.getScheme().equals(url.getScheme())
                || !origin.getAuthority().equals(url.getAuthority()) || url.getUserInfo() != null
                || !("/launcher/authorize/" + receivedCode).equals(url.getPath())
                || url.getQuery() != null || url.getFragment() != null)
            throw new IOException("服务器返回了无效的浏览器授权地址");
        code = receivedCode;
        return url;
    }

    /// Returns null while waiting for website approval and consumes a successful grant once.
    @Nullable Credentials poll() throws IOException, InterruptedException {
        if (code == null) throw new IllegalStateException("Authorization has not started");
        JsonObject request = new JsonObject();
        request.addProperty("code", code);
        request.addProperty("verifier", verifier);
        request.addProperty("state", state);
        HttpResponse<String> response = post("token", request);
        if (response.statusCode() == 202) return null;
        if (response.statusCode() == 410) {
            throw new AuthorizationExpiredException(expiredReason(response.body()), fingerprint(code));
        }
        requireSuccess(response);
        return decode(parse(response.body()), state, clientToken);
    }

    /// Indicates a server-side grant rejection that may be transient during login redirect.
    static final class AuthorizationExpiredException extends IOException {
        /// Safe server-side reason returned for diagnostics; it never contains request secrets.
        private final String serverReason;
        /// Short digest of the authorization code used to correlate server logs without exposing it.
        private final String codeFingerprint;

        /// Creates a retryable expired-grant error without exposing response data.
        AuthorizationExpiredException(String serverReason, String codeFingerprint) {
            super("登录授权已失效，请重新登录");
            this.serverReason = serverReason;
            this.codeFingerprint = codeFingerprint;
        }

        /// Returns the sanitized server error identifier and message summary.
        String serverReason() { return serverReason; }

        /// Returns the non-reversible code fingerprint used to correlate one login attempt.
        String codeFingerprint() { return codeFingerprint; }
    }


    /// Parses only a state-bound real Yggdrasil session.
    static Credentials decode(JsonObject result, String state, String clientToken) throws IOException {
        try {
            if (!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                    required(result, "state").getBytes(StandardCharsets.UTF_8))) throw new IOException("登录授权状态不匹配");
            JsonObject session = result.getAsJsonObject("session");
            if (session == null || !clientToken.equals(required(session, "clientToken")))
                throw new IOException("游戏会话客户端标识不匹配");
            String accessToken = required(session, "accessToken");
            String login = required(result, "login");
            String player = required(result, "player");
            List<GameProfile> profiles = new ArrayList<>();
            @Nullable GameProfile chosen = null;
            JsonArray available = session.getAsJsonArray("availableProfiles");
            if (available == null) throw new IOException("账户尚未创建游戏角色");
            for (JsonElement element : available) {
                JsonObject profile = element.getAsJsonObject();
                GameProfile parsed = new GameProfile(UUIDs.parse(required(profile, "id")), required(profile, "name"));
                profiles.add(parsed);
                if (player.equals(parsed.getName())) chosen = parsed;
            }
            if (chosen == null) throw new IOException("授权角色不属于返回的游戏账户");
            @Nullable GameProfile selected = null;
            if (session.has("selectedProfile") && session.get("selectedProfile").isJsonObject()) {
                UUID selectedId = UUIDs.parse(required(session.getAsJsonObject("selectedProfile"), "id"));
                for (GameProfile profile : profiles) if (selectedId.equals(profile.getId())) selected = profile;
                if (selected == null) throw new IOException("服务器返回了不属于该账户的角色");
            }
            return new Credentials(login, chosen, new YggdrasilSession(clientToken, accessToken, selected,
                    List.copyOf(profiles), Map.of()));
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
            throw new IOException("登录授权响应格式无效", e);
        }
    }

    /// Posts secrets in a JSON body to the trusted origin, never in URLs or logs.
    private HttpResponse<String> post(String endpoint, JsonObject request) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(origin.resolve("/api/freecore-launcher/" + endpoint))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    /// Creates an authorization coordinator for the configured FreeCore account origin.
    static FreeCoreBrowserAuthorization configured() throws IOException {
        try {
            URI login = URI.create(FreeCoreBootstrap.getLoginUrl());
            if (!"https".equalsIgnoreCase(login.getScheme()) || login.getHost() == null
                    || login.getUserInfo() != null || login.getQuery() != null || login.getFragment() != null)
                throw new IOException("FreeCore 登录地址不安全");
            return new FreeCoreBrowserAuthorization(new URI(login.getScheme(), login.getAuthority(), null, null, null));
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException("FreeCore 登录地址无效", e);
        }
    }

    /// Rejects server failures without displaying response bodies which might contain credentials.
    private static void requireSuccess(HttpResponse<String> response) throws IOException {
        if (response.statusCode() != 200) throw new IOException("皮肤站授权失败（HTTP " + response.statusCode() + "）");
    }

    /// Parses a JSON object without accepting malformed or HTML responses.
    private static JsonObject parse(String body) throws IOException {
        try {
            @Nullable JsonObject object = JsonUtils.GSON.fromJson(body, JsonObject.class);
            if (object == null) throw new IOException("皮肤站未返回授权信息");
            return object;
        } catch (RuntimeException e) { throw new IOException("皮肤站授权响应格式无效", e); }
    }

    /// Extracts only bounded, printable error fields from a 410 response for local diagnostics.
    private static String expiredReason(String body) {
        try {
            @Nullable JsonObject object = JsonUtils.GSON.fromJson(body, JsonObject.class);
            if (object == null) return "no-json";
            String error = diagnosticField(object, "error");
            String message = diagnosticField(object, "message");
            if (error.equals("-") && message.equals("-")) return "no-details";
            return error + ":" + message;
        } catch (RuntimeException ignored) {
            return "invalid-json";
        }
    }

    /// Reads one non-sensitive diagnostic field and bounds its size before it reaches the log.
    private static String diagnosticField(JsonObject object, String name) {
        @Nullable String value = JsonUtils.getString(object, name);
        if (value == null || value.isBlank()) return "-";
        String normalized = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }

    /// Computes an eight-byte correlation fingerprint without exposing the one-time code.
    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) result.append(String.format("%02x", digest[i]));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /// Reads a required nonblank string from a protocol message.
    private static String required(JsonObject object, String name) throws IOException {
        @Nullable String value = JsonUtils.getString(object, name);
        if (value == null || value.isBlank()) throw new IOException("授权响应缺少 " + name);
        return value;
    }

    /// Computes the RFC 7636 S256 challenge without padding.
    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    /// Generates 256 bits of cryptographic entropy for one authorization attempt.
    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /// Approved game identity belonging to exactly one provider account.
    /// @param login account identifier returned by the provider
    /// @param profile account-owned character explicitly approved in the browser
    /// @param session real provider-issued Yggdrasil session, validated before account registration
    @NotNullByDefault
    record Credentials(String login, GameProfile profile, YggdrasilSession session) {}
}
