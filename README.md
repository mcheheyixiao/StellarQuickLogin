# StellarQuickLogin

StellarQuickLogin 是一个最小可用的 Paper 插件，用于把 StellarWorld 网站发出的 5 分钟免密登录票据安全地桥接到 AuthMeReloaded 5.6.0。插件只在“收到有效 ticket 且网站 consume 成功”时调用 AuthMe `forceLogin`，不会直接改 AuthMe 数据库，也不会因为网站或 Realtime 故障绕过正常密码登录。

## 依赖

- Paper 1.20.x
- Java 17
- AuthMeReloaded 5.6.0
- StellarWorld 内部 consume API
- 可选：StellarRealtime `/ws/plugin`

## 安装

1. 使用 `./gradlew clean build` 构建插件。
2. 把生成的 `build/libs/StellarQuickLogin-1.0.0.jar` 放进 Paper 服务器的 `plugins/`。
3. 安装 AuthMeReloaded 5.6.0。
4. 如果本地构建环境无法直接从远端拉取 AuthMe 依赖，把 `AuthMe-5.6.0.jar` 放到仓库根目录的 `libs/`，不要提交这个 jar。
5. 启动服务器后修改 `plugins/StellarQuickLogin/config.yml`。

## 配置项

### `server-id`

- 当前 Minecraft 服务器在 StellarWorld / StellarRealtime 体系中的唯一标识。

### `authme`

- `enabled`: 是否启用 AuthMe 集成。
- `join-delay-ticks`: 玩家进服后延迟多少 tick 再尝试 quick login。
- `require-registered`: 是否要求玩家已经在 AuthMe 中注册。

### `website`

Quick notes for the current website contract:

- `enabled-direct-check` should stay enabled if you want re-login during the 5-minute quick-login window after the one-time ticket has already been cleared locally.
- Default `enabled-direct-check` is `true`.
- The plugin now forwards the player's real connection IP as `clientIp` when it can read `player.getAddress()`.

- `enabled-direct-check`: 没有收到 Realtime 预授权时，是否在玩家进服后直接请求 consume API 检查 pending ticket、active window 或 trusted IP。默认 `true`。
- `consume-url`: StellarWorld 内部 consume 接口地址。
- `internal-token`: StellarWorld 内部 Bearer token。保留 `CHANGE_ME` 时插件会禁用 quick login 功能。
- `timeout-ms`: consume API 超时时间。

### `realtime`

- `enabled`: 是否启用 StellarRealtime WebSocket 客户端。
- `websocket-url`: StellarRealtime `/ws/plugin` 地址。
- `plugin-token`: Realtime 插件鉴权 token。
- `reconnect-delay-seconds`: 断线重连间隔。
- `heartbeat-seconds`: 心跳间隔。

### `security`

- `ticket-ttl-seconds`: 本地内存 ticket 最大保留时长。
- `require-player-match`: 是否要求 ticket 的玩家身份与进服玩家完全匹配。
- `clear-ticket-after-attempt`: quick login 尝试后是否立即清理本地 ticket。
- `log-sensitive-values`: 预留的调试开关；插件仍然不会把 ticket token 或内部 token 打到日志。

### `messages`

- `success`: quick login 成功提示。
- `expired`: ticket 过期提示。
- `failed`: consume 或 AuthMe 登录失败提示。

## 接口关系

### StellarRealtime

插件连接 `realtime.websocket-url`，首帧发送：

```json
{
  "type": "auth",
  "role": "plugin",
  "token": "CHANGE_ME",
  "serverId": "survival-1",
  "plugin": "StellarQuickLogin"
}
```

插件接收 `quicklogin.preauthorize`，兼容以下消息类型字段：

- `type`
- `event`
- `action`
- `command.type`

收到有效预授权后，插件只缓存 ticket，不直接放行玩家登录。

### StellarWorld consume API

玩家进服时，插件会先查本地缓存；有 ticket 时向 `website.consume-url` 发送：

```json
{
  "token": "ticket-from-website",
  "playerName": "Alice",
  "playerUuid": "123e4567-e89b-12d3-a456-426614174000",
  "serverId": "survival-1",
  "clientIp": "203.0.113.42"
}
```

HTTP Header:

- `Authorization: Bearer <website.internal-token>`
- `Content-Type: application/json`

When `website.enabled-direct-check` is enabled and no local Realtime ticket exists, the plugin sends the same JSON shape without the `token` field and still includes `clientIp` when available. The website can then authorize from a pending ticket, an active 5-minute login window, or a trusted IP record.

如果启用了 `website.enabled-direct-check`，并且本地没有收到 Realtime 票据，插件会发送不带 `token` 字段的同类请求，让网站自行按玩家身份决定是否存在 pending ticket。

### AuthMeReloaded 5.6.0

插件目标兼容 `fr.xephi.authme.api.v3.AuthMeApi`，实际核对的 5.6.0 方法签名包括：

- `AuthMeApi.getInstance()`
- `isAuthenticated(Player)`
- `isRegistered(String)`
- `forceLogin(Player)`

运行时通过 `AuthMeHook` 隔离调用；如果 AuthMe 未安装、未启用或 API 初始化失败，插件只打印警告，不会崩服。

## 安全注意事项

- ticket token 只保存在内存里，不会落盘。
- 插件不会把 ticket token、网站内部 token、Realtime token 打到日志。
- 票据过期、serverId 不匹配、consume 失败时，玩家仍然需要正常 `/login`。
- 插件不会因为网站或 Realtime 异常绕过 AuthMe。
- 插件不会直接修改 AuthMe 数据库。
- `forceLogin` 只在 Bukkit 主线程调用。

## 管理命令

- `/stellarquicklogin status`
- `/stellarquicklogin reload`
- 别名：`/sqlogin`
- 权限：`stellarquicklogin.admin`

## 手工测试步骤

1. AuthMe 未安装时启动服务器，确认插件只警告，不会崩服。
2. 安装 AuthMe 但不给玩家 ticket，确认玩家仍需正常 `/login`。
3. 在网站点击免密后，通过 StellarRealtime 下发 `quicklogin.preauthorize`，确认插件缓存 ticket。
4. 玩家在 5 分钟内进服，consume 成功后，确认 AuthMe `forceLogin` 生效。
5. 使用同一 ticket 第二次进服，确认不能再次免密登录。
6. 发送过期 ticket，确认玩家不能免密登录，并收到过期或失败提示。
7. 把 `website.internal-token` 配错，确认 quick login 失败，玩家仍需 `/login`。
8. 断开 StellarRealtime，确认插件会按配置自动重连。
