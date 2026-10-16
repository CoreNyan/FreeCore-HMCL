# 服务端追加修复：账号密码登录丢失 FreeCore 授权回跳

现象：Microsoft 授权可以完成，但在 `/auth/login` 页面直接输入账号密码后，登录成功没有回到原来的 `/launcher/authorize/{code}`，最终授权页显示“授权请求不存在或已过期”，启动器收到 `HTTP 410`。

根因：普通账号密码登录使用 Blessing Skin 的 AJAX 登录接口 `/auth/login`，前端根据响应中的 `data.redirectTo` 跳转。当前密码登录成功分支仍返回 `/user` 或默认地址，没有复用 FreeCore 授权页保存的 pending 回跳；Microsoft 登录分支已经单独处理，所以表现不一致。

请在服务端修复普通密码登录成功分支：

1. `GET /launcher/authorize/{code}` 未登录时，将完整的 `/launcher/authorize/{code}` 写入 Laravel session，例如 `freecore_launcher_pending` 和 `url.intended`。登录页只允许携带站内相对路径，禁止接受外部 URL。
2. 处理普通账号密码登录成功时，优先读取并校验 `freecore_launcher_pending` / `url.intended`。只有路径严格匹配 `/launcher/authorize/[a-f0-9]{64}` 时才使用它。
3. AJAX 登录响应必须把该原始授权地址放进 `data.redirectTo`，例如：
   ```json
   {"code":1,"data":{"redirectTo":"/launcher/authorize/<same-code>"}}
   ```
   禁止在存在 pending 授权时返回 `/user`、首页或不带 Code 的地址。
4. 如果服务端使用 `auth.login.succeeded` 事件监听器，也必须覆盖普通密码登录和 Microsoft 登录两条成功路径，统一调用同一个回跳函数。不要只在 Microsoft 插件回调中处理。
5. 登录成功回跳期间禁止执行 `Cache::forget`、`Cache::pull`、取消授权或重新创建 Code。原 Code 的状态必须仍为 `pending`，token 轮询继续返回 `202`。
6. 回跳地址消费后再清理 `freecore_launcher_pending`，但不要清理授权 Code 缓存；只有用户点击允许、取消、过期或 token 成功消费时才改变授权状态。
7. 确认密码登录 POST 与授权页 GET 使用同一个 Redis session、同一个 session cookie 域、同一个应用实例；不要让 AJAX 登录请求切换到不同 session 驱动。

必须用真实账号密码验收：

- 创建一个授权 Code；
- 未登录打开授权页并跳转 `/auth/login`；
- 直接输入账号密码提交；
- 检查登录 AJAX 响应的 `data.redirectTo` 必须是同一个 `/launcher/authorize/{code}`；
- 登录成功后授权页返回 `HTTP 200`，不能显示“授权请求不存在或已过期”；
- 授权确认前 token 返回 `202 pending`；
- 点击允许后第一次 token 返回 `200`，第二次返回 `410 consumed`。

请同时提供密码登录成功响应中 `data.redirectTo` 的脱敏值，以及该 Code 的状态变迁日志。不要通过自动刷新、重复打开窗口或创建新 Code 规避问题。
