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

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies passwordless grants resolve only to state-bound account-owned game sessions.
@NotNullByDefault
final class FreeCoreBrowserLoginServerTest {

    /// Matches the published RFC 7636 S256 test vector.
    @Test
    void pkceMatchesRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                FreeCoreBrowserAuthorization.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    /// A third-party login may return a complete game session without any password field.
    @Test
    void passwordlessGrantSelectsOnlyApprovedCharacter() throws Exception {
        var credentials = FreeCoreBrowserAuthorization.decode(grant(), "state", "client");
        assertEquals("Alice", credentials.profile().getName());
        assertEquals("issued-game-token", credentials.session().getAccessToken());
    }

    /// Grants from a different request or client cannot register an account.
    @Test
    void rejectsMismatchedStateAndClient() {
        assertThrows(IOException.class, () -> FreeCoreBrowserAuthorization.decode(grant(), "other", "client"));
        assertThrows(IOException.class, () -> FreeCoreBrowserAuthorization.decode(grant(), "state", "other"));
    }

    /// An approved profile must actually be owned by the returned game session.
    @Test
    void rejectsForeignProfileAndMissingGameToken() {
        JsonObject foreign = grant();
        foreign.addProperty("player", "Bob");
        assertThrows(IOException.class, () -> FreeCoreBrowserAuthorization.decode(foreign, "state", "client"));
        JsonObject cookieOnly = grant();
        cookieOnly.getAsJsonObject("session").remove("accessToken");
        assertThrows(IOException.class, () -> FreeCoreBrowserAuthorization.decode(cookieOnly, "state", "client"));
    }

    /// Isolated fixture models a provider-issued session, never a production account.
    private static JsonObject grant() {
        return JsonUtils.GSON.fromJson("""
                {"state":"state","login":"alice@example.invalid","player":"Alice",
                 "session":{"clientToken":"client","accessToken":"issued-game-token",
                   "availableProfiles":[{"id":"00000000000000000000000000000001","name":"Alice"}]}}
                """, JsonObject.class);
    }
}
