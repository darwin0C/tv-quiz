# 电视局域网接口（家长 App 用）

电视端「答题看电视」内置的 HTTP 服务，Android 和 iOS 家长 App 都通过它与电视通信。

- 地址：`http://<电视IP>:8765`，只在家庭局域网内使用（明文 HTTP）
- 发现：mDNS / Bonjour 服务类型 `_tvquiz._tcp`，服务名 `答题看电视-<型号>`
- 数据：JSON，UTF-8
- 认证：除 `/api/info` 和 `/api/pair` 外，都要带请求头 `Authorization: Bearer <token>`
- 出错：非 2xx 状态码，返回 `{"error": "中文说明"}`；401 表示未配对或令牌已被电视取消

## 配对

1. 家长在电视「家长 → 家长手机配对」点「生成配对码」，得到 6 位数字（10 分钟有效，用一次即失效）。
2. 手机 `POST /api/pair`，拿到令牌并保存。可以配对多台手机；电视上「取消所有手机配对」会让所有令牌失效。

| 接口 | 说明 |
|---|---|
| `GET /api/info` | `{app:"tvquiz", version, device, pairing}`，用来确认地址是这台电视 |
| `POST /api/pair` `{code, device}` | 返回 `{token, device}` |

## 状态与时间

| 接口 | 说明 |
|---|---|
| `GET /api/status` | 余额、今日观看（含家长模式部分）、今日答题、错题数、模式、权限状态 |
| `POST /api/balance` `{set_minutes}` 或 `{add_minutes}` | 手动设定或增加答题余额，范围 0 到 60 分钟；返回最新状态 |
| `POST /api/unlock` `{minutes}` | 家长模式：解锁 N 分钟，期间不扣余额、不拦截；`0` 表示结束解锁 |

`status` 字段：`balance_seconds`、`cap_seconds`、`today_watched_seconds`、`today_parent_watch_seconds`、`today_earned_seconds`、`today_answers`、`today_correct`、`wrong_count`、`mode`（`quiz`/`parent`）、`unlock_remaining_seconds`、`guard_enabled`、`accessibility_on`、`notification_access_on`、`custom_count`。

## 学习设置

| 接口 | 说明 |
|---|---|
| `GET /api/catalog` | 题库目录：每册 → 学科 → 单元（序号 `ui`、名称、题数），以及题型名称 |
| `GET /api/settings` | `{book, subjects, types, max_unit:{学科: 单元序号或 null}, guard_enabled}` |
| `PUT /api/settings` | 只改传入的字段。`max_unit` 为 null 表示不限单元；进度按当前册次分别保存 |

题型：`choice`（原选择题）、`fill`（原填空题，已转为选择题）、`custom`（拍照导入题）。

## 记录

| 接口 | 说明 |
|---|---|
| `GET /api/answers?limit=100` | 最近答题记录 |
| `GET /api/wrong` | 错题：答错次数、之后答对次数、最近时间 |
| `GET /api/apps` / `PUT /api/apps {managed:[包名]}` | 电视上的应用和受管状态 |

## 拍照导入题

识别和出题在手机上完成（调用 Claude），家长核对后才提交到电视。

| 接口 | 说明 |
|---|---|
| `GET /api/custom` | 已导入的题 |
| `POST /api/custom` `{questions:[...]}` | 返回 `{added, errors}`，不合格的题会逐条说明原因 |
| `DELETE /api/custom?id=...` | 删除一道导入题 |

题目格式（与内置题库相同）：

```json
{"sub": "数学", "book": "一年级上册", "ui": 2, "u": "单元名", "l": "课题",
 "q": "3 + 4 = ?", "a": "7", "o": ["6", "7", "8", "9"], "x": "解析（可选）"}
```

校验：必须有 `sub/book/q/a/o`；`book` 是六册之一；选项 2 到 6 个、不重复；答案必须在选项里。

## iOS 版实现要点

- `Info.plist` 需要 `NSLocalNetworkUsageDescription` 和 `NSBonjourServices: ["_tvquiz._tcp"]`，否则 iOS 不允许访问局域网和发现电视
- 局域网是明文 HTTP，需要 ATS 例外 `NSAllowsLocalNetworking = YES`
- 用 `NWBrowser` 发现服务，`URLSession` 调接口，界面和流程与 Android 版一致
