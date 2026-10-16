# 给服务端 AI 的实施任务：Blessing Skin 启动器授权接口

你正在维护一个 **Blessing Skin 6.0.2** 实例，运行在 MCSManager 管理的容器中。MCSManager 只负责启动和管理容器；本任务必须作为 Blessing Skin 插件或现有 FreeCore 插件的扩展实现。

目标：用户在系统默认浏览器完成 Blessing Skin 登录并明确点击授权后，FreeCore HMCL 启动器可以安全取得：

1. 当前 Blessing Skin 用户的登录标识；
2. 当前用户选中的游戏角色；
3. 由该站点 Yggdrasil 服务签发的真实游戏 session；
4. 当前用户个人中心使用的网页 cookie。

启动器密码、网页 session 和游戏 access token 不能出现在 URL、日志或前端可读的 query 参数中。

## 必须先做的代码审计

在修改代码前，先检查当前实例的：

- Blessing Skin 版本和插件加载机制；
- Laravel 路由注册方式和 CSRF 中间件；
- 当前用户模型、角色模型及角色选择逻辑；
- 当前 Yggdrasil 服务生成/刷新 session 的 service；
- 当前 session/cache 驱动是否支持带 TTL 的原子写入；
- 已安装 `freecore-ms-verify` 插件，避免覆盖它已有的路由或前端脚本。

不要假设 MCSManager 提供用户认证 API。认证真相源是 Blessing Skin 自己的 Laravel session。

## API 一：创建授权请求

注册：

```text
POST /api/freecore-launcher/start
Content-Type: application/json
Accept: application/json
```

请求体：

```json
{
  "challenge": "RFC7636-S256-base64url-verifier",
  "state": "launcher-random-state",
  "clientToken": "yggdrasil-client-token"
}
```

服务端要求：

- 三个字段必须是非空字符串；
- `challenge` 必须是合法的 base64url 字符串；
- 生成至少 256 bit 随机数作为 `code`；
- `code` 使用 64 位小写十六进制字符串；
- 通过 Redis 或当前可用 cache 驱动保存授权状态，TTL 为 10 分钟；
- cache key 必须包含随机 code，不能使用用户邮箱或角色名；
- 不记录请求体，不记录 verifier、token 或 cookie；
- 对 IP 和频率进行限制。

成功响应 `200`：

```json
{
  "code": "64-lowercase-hex-characters",
  "authorizationUrl": "https://account.lynnhma.xyz/launcher/authorize/<code>"
}
```

`authorizationUrl` 必须是当前站点 HTTPS 同源地址，不能带 query、fragment 或 user-info。

## API 二：浏览器授权页面

注册：

```text
GET /launcher/authorize/{code}
```

行为：

1. 从 cache 读取待授权请求；不存在或过期时返回明确错误页，不执行任何 token 操作；
2. 没有 Blessing Skin 登录 session 时，跳转到现有 `/auth/login`，登录完成后回到本页面；
3. 已登录时，从服务端 session 读取当前用户；禁止从 URL、隐藏表单或浏览器提交值读取用户身份；
4. 展示用户标识和角色列表；角色必须来自当前用户实际拥有的角色；
5. 要求用户明确点击“允许 FreeCore 启动器登录”；
6. 提供“取消”按钮。取消后删除 cache，后续 token 请求返回 `410`；
7. 页面只提交角色选择和 CSRF token，不能提交密码、cookie、access token。

### 登录回跳的强制要求

未登录跳转时，服务端必须把完整的 `/launcher/authorize/{code}` 保存到 Laravel session 或签名的 `return` 参数中。登录成功后必须精确回到同一个 code 的授权页面，不能跳转到 `/user`、站点首页或一个不带 code 的地址。原授权 cache 在未登录跳转期间不得被删除；只有明确取消、过期或成功消费时才失效。

授权确认后，服务端必须在服务器端完成：

- 根据当前用户和角色调用 Blessing Skin 现有 Yggdrasil 服务；
- 生成或刷新真实的 `accessToken`；
- 生成 `selectedProfile` 和 `availableProfiles`；
- 读取当前登录用户对应的网页 cookie；
- 将结果写回原授权 cache，并标记为 `approved`。

不要自己伪造 UUID、access token 或离线 session。不能把网页 cookie 当作游戏 token。

## API 三：轮询授权结果

注册：

```text
POST /api/freecore-launcher/token
Content-Type: application/json
Accept: application/json
```

请求体：

```json
{
  "code": "pending-code",
  "verifier": "original-launcher-verifier",
  "state": "original-launcher-state"
}
```

处理流程：

1. 读取授权 cache；
2. 使用 `SHA-256(verifier)` 的 base64url 无 padding 结果恒定时间比较 `challenge`；
3. 恒定时间比较 `state`；
4. 比较 `clientToken`；
5. 未授权时返回 `202`，响应体不得包含账户信息；
6. 过期、取消、校验失败或已消费时返回 `410`；
7. 授权成功时使用原子操作消费 cache，只允许成功返回一次；
8. 返回下列 JSON，禁止返回额外敏感字段。

成功响应 `200`：

```json
{
  "state": "original-launcher-state",
  "login": "account-login-name",
  "player": "selected-player-name",
  "session": {
    "clientToken": "the-start-client-token",
    "accessToken": "real-yggdrasil-access-token",
    "selectedProfile": {
      "id": "compact-or-dashed-uuid",
      "name": "Player"
    },
    "availableProfiles": [
      {
        "id": "compact-or-dashed-uuid",
        "name": "Player"
      }
    ]
  },
  "cookies": {
    "BS_SESSION": "website-session-cookie"
  }
}
```

cookie 要求：

- 只返回当前已批准用户自己的网页 session cookie；
- cookie 名称匹配 `[A-Za-z0-9_-]+`；
- cookie 值不能包含控制字符或 `;`；
- 不返回密码、CSRF token、管理员 cookie、第三方 OAuth cookie 或其他用户 cookie；
- 不在日志中输出 cookie。

## 安全要求

- 全部接口必须使用 HTTPS；
- `start`、`token`、授权页面必须限制请求频率；
- 授权 code 使用一次即失效；
- 授权记录 TTL 不超过 10 分钟；
- 所有失败响应使用通用错误信息，不泄露用户是否存在；
- 所有写操作使用 Laravel CSRF 保护；
- 禁止在 URL 中放置 access token、网页 cookie、密码或 verifier；
- 授权页必须防止 clickjacking，设置合适的 CSP、`frame-ancestors` 和 `SameSite` cookie 策略；
- 不要修改现有 Yggdrasil API 的响应格式；
- 不要删除或覆盖 `freecore-ms-verify` 的路由。

## 验收测试

服务端 AI 完成后必须执行并报告以下结果：

1. 未登录访问授权页会跳转登录，登录后能回到授权页；
2. 点击取消后轮询返回 `410`；
3. 未授权轮询返回 `202`；
4. 错误 verifier、state 或 clientToken 不会返回 session；
5. 同一个授权 code 第二次消费返回 `410`；
6. 授权后返回的 access token 能通过现有 Yggdrasil `validate`；
7. `selectedProfile` 属于 `availableProfiles` 且属于当前用户；
8. 返回的网页 cookie 能在 `https://account.lynnhma.xyz/user` 打开当前用户个人中心；
9. 过期授权记录会被清理；
10. 日志和错误追踪中没有密码、verifier、access token 或 cookie。

完成后请提供：插件目录、安装步骤、数据库/cache 迁移步骤、实际接口 URL、测试命令和一份脱敏的请求/响应示例。
