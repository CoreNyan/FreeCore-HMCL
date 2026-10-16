# FreeCore Launcher Browser Authorization API

The launcher uses a one-time authorization code with an RFC 7636 S256 verifier. The web server remains the only component that can read the authenticated website session and create a real Yggdrasil game session.

## `POST /api/freecore-launcher/start`

Request:

```json
{
  "challenge": "base64url-sha256(verifier)",
  "state": "launcher-random-state",
  "clientToken": "yggdrasil-client-token"
}
```

The server must validate that `challenge`, `state`, and `clientToken` are non-empty, generate at least 256 bits of random `code`, and store a pending grant for at most 10 minutes. The response is:

```json
{
  "code": "64-lowercase-hex-characters",
  "authorizationUrl": "https://account.lynnhma.xyz/launcher/authorize/<code>"
}
```

The authorization URL must have the same HTTPS origin, contain no query, fragment, or user-info, and require the normal website login. The authorization page must show the logged-in account and an explicit approval action.

## `POST /api/freecore-launcher/token`

Request:

```json
{
  "code": "pending-code",
  "verifier": "original-launcher-verifier",
  "state": "original-launcher-state"
}
```

Responses:

- `202` while the user has not approved the request.
- `410` when the code expired, was cancelled, or was already consumed.
- `200` after approval, with the following body:

```json
{
  "state": "original-launcher-state",
  "login": "account-login-name",
  "player": "selected-player-name",
  "session": {
    "clientToken": "the-start-client-token",
    "accessToken": "real-yggdrasil-access-token",
    "selectedProfile": {"id": "compact-or-dashed-uuid", "name": "Player"},
    "availableProfiles": [{"id": "compact-or-dashed-uuid", "name": "Player"}]
  },
  "cookies": {
    "BS_SESSION": "website-session-cookie"
  }
}
```

The token endpoint must compare the stored S256 challenge with `SHA-256(verifier)`, compare `state` and `clientToken`, atomically consume the grant, and return only cookies belonging to the approved website account. Cookie names must match `[A-Za-z0-9_-]+`; values must not contain control characters or `;`.

## Approval rules

The approval page must derive the account and player from the server-side authenticated session. It must never accept `login`, `player`, `accessToken`, or cookies from browser query/form parameters. On approval, the server should call the existing Yggdrasil session service to issue or refresh the real game session, then store the result with the pending grant. A cancel action should consume the grant and make subsequent polls return `410`.

The server should rate-limit `start` and `token` by account and source IP, avoid logging request bodies, and purge expired grants continuously.
