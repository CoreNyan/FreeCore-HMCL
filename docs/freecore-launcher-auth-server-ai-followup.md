# 服务端 AI 追加修复任务：登录回跳后授权 Code 失效

线上实际现象：启动器创建授权请求后，默认浏览器可以完成登录并跳回授权页，但授权页显示“授权未完成，该授权已失效或被拒绝”，启动器同时收到 `HTTP 410`。用户未要求新增窗口或自动创建新的授权 Code；必须修复原 Code 的生命周期。

## 必须修复

对同一个 `{code}` 完成一次完整链路审计：

1. `GET /launcher/authorize/{code}` 只能使用 `Cache::get`（或等价的非消费读取）。渲染页面、登录跳转、登录成功回跳都禁止 `pull`、`forget`、改变状态或刷新 TTL。
2. 未登录跳转必须保留原始 `/launcher/authorize/{code}`。登录成功后必须回到同一个 `{code}`，不能生成新 Code、不能跳 `/user`、不能跳首页。
3. 点击“允许”时必须在服务器端读取当前登录用户和角色，并对原缓存记录做保留字段的原子更新。更新后的记录必须继续包含原始 `challenge`、`state`、`clientToken` 和未过期的 `expiresAt`；禁止用不带 TTL 的新写入覆盖原记录。
4. 授权批准只能把状态从 `pending` 改为 `approved`。批准前 token 接口始终返回 `202 {"status":"pending"}`；批准后才允许返回 `200` 并一次性消费。
5. `POST /api/freecore-launcher/token` 返回 `410` 时必须区分并返回脱敏的 `error`：`invalid_verifier`、`invalid_state`、`expired`、`cancelled`、`consumed`、`not_found`。不得把所有分支混成无法诊断的同一结果。
6. 启动器创建请求、浏览器授权页、登录回跳、批准提交、token 轮询必须使用同一个共享 cache/Redis 后端。禁止使用仅对单个 PHP worker 可见的 `array`、临时目录或本地不共享文件缓存。部署多个实例时必须确认读写落到同一后端。
7. 每个授权 Code 只记录不可逆的短指纹和状态变迁：`created -> pending -> approved -> consumed`，或 `cancelled/expired`。日志禁止记录 verifier、access token、cookie、密码和完整 Code。

## 必须提供的验收证据

使用一次真实浏览器登录，记录同一个 Code 指纹在以下时刻的状态：创建、未登录授权页、登录回跳、授权页展示、点击允许、第一次轮询、第二次轮询。预期结果：

- 登录回跳前后 Code 指纹相同；
- 登录回跳和授权页展示期间状态仍为 `pending`，轮询返回 `202`；
- 点击允许后状态变为 `approved`，第一次轮询返回 `200`；
- 第二次轮询返回 `410 {"error":"consumed"}`；
- 若授权页在登录回跳后返回 `410`，必须给出该 Code 的最后状态、缓存后端名称、`expiresAt` 和实际命中的实例标识，证明究竟是过期、被消费、被取消还是缓存不共享。

不要通过自动刷新、重复打开浏览器或创建新授权 Code 来规避问题。
