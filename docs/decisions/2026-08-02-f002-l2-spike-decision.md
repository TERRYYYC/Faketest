---
feature_ids: [F002]
topics: [android, fake-gps, precise-location, spike, decision]
doc_kind: decision
created: 2026-08-02
---

# F002 L2 Spike 决策记录：精确选点路径选型

> **Date**: 2026-08-02 | **By**: 墨墨/Kimi K3 | **Device**: moto g54 5G (`ZY22JHW9M4`, Android 15, Magisk+Zygisk Vector)
> **Conclusion**: winner = **路径 3（`name.caiyao.fakegps` 档案直输）**，路径 1/2 有证据判死；winner 落地有一个契约级前置（isMock 诚实标记），已出 Decision Packet 给 operator。

## Spike 方法与证据

### 路径 1：hopefactory 长按 pin —— 判死（geocoding 吸附实锤）

- 实验：在 hopefactory 地图随意长按一点 → 街道标签跳变为 `Elektrykiv Street, 16`；随后 Start Fake GPS，`dumpsys location` 显示实际注入 mock 坐标 `50.479954,30.522806`（gps+fused 均带 `mock` 标志，fix 新鲜）。
- 随机长按恰好命中一个带门牌号地址到 6 位小数的概率为零 → 选点被吸附到最近 geocoded 地址，与 issue #3 结论一致。
- 补充：raw `lat,lng` 搜索无结果（spec verified fact #1 复测成立，搜索框为 Leku `leku_search`），无法把相机精确导航到任意坐标；joystick 走点对 km 级目标不可行。

### 路径 2：favorites —— 判死（继承吸附）

- hopefactory 有 "Save location"（长按后出现），但收藏保存的是**已吸附的 pin 坐标**；无法以精确坐标创建收藏。caiyao 的"收藏档案"见路径 3。

### 路径 3：`name.caiyao.fakegps` —— 精确，可用，有前置条件

**它是 operator 自己的 Xposed 模块**（设置页 GitHub: `TERRYYYC/FakeGps-test`，v3.0.0），fork 自 caiyao FakeGPS，经 Zygisk Vector（`/data/adb/modules/zygisk_vector`，LSPosed fork）注入目标进程，hook 80+ 系统 API（定位/基站/WiFi/IP），README 明确覆盖 `getLastKnownLocation()`、`getCurrentLocation()` (API 30+)、FusedLocationProviderClient —— 正是 L1 闸门要用的 API。

- **精确性（构造层面）**：档案编辑器支持**文本直输经纬度**（如 `50.21115235930687`，全 double 精度），另有海拔/速度/方向/精度字段；搜索对话框直接接受 `纬度,经度` 文本并把地图导航到目标（实测 `50.4789,30.5490` 精确导航到第聂伯河中点，pin 坐标气泡 6 位小数）。零 geocoding、零吸附。
- **hook 存活证据**：设置页 scope 数据库（`/data/adb/lspd/config/modules_config.db`）显示模块已启用，scope 已含 `com.cellrebel.mobile`（operator 预先配置）、Google Maps、Cellular-Pro/Z；caiyao"验证"页报告 16 字段已生效、运行时探针已连接。
- **配置通道**：档案 → SQLite → XSharedPreferences → hook 进程 `AtomicReference<Snapshot>`；`AppInfoProvider` `exported=false`（设计如此，防被测 app 读穿）。**无对外自动化 API**，自动化走 UI（a11y `ACTION_SET_TEXT`，spec verified fact #3 可靠）。

## 契约冲突（L1 mock-required vs caiyao）

`HookUtils.createFakeLocation()` 用 `new Location(GPS_PROVIDER)` 构造假位置（全源码零 `mock` 字样）→ hook 交付的 fix **isMock=false**。spec v2.1 固定契约 "mock 必须、无条件" 会把 caiyao 的精确 spoof 判为 `LOCATION_NOT_MOCKED`。

另外我们的 app（`com.example.cellrebelauto`）**不在 Vector scope 里** —— 不加进去，L1 闸门在 app 内读到的是真实 GPS，而非 CellRebel 所见的 spoof。

## 选项（Decision Packet 已发 operator）

| 选项 | 内容 | 代价 | 评价 |
|------|------|------|------|
| **A（推荐）** | caiyao 仓库一行补丁：`createFakeLocation` 里 `setMock(true)`（API 31+），重新构建安装模块；把我们的 app 加进 scope | 改另一个仓库（1 commit 可 revert）；模块重装可能需重启；scope 勾选可逆 | L1 契约零改动；spoof 诚实自标；两端（闸门与 CellRebel）看到同一个 spoof |
| B | 修 L1 契约，承认 "caiyao hook 验证通道" 为 mock 等价证据 | 同样需要改 caiyao 暴露验证 API（provider exported=false）；弱化反泄漏单一信号契约 | 被 A 支配（dominated） |
| C | 都不做，留在 hopefactory | L2 失败，按 spec fallback F002 保持 open | 不解决 operator 的地址问题 |
| D（备选） | 混合：hopefactory 保持 mock（供 isMock=true），caiyao hook 在 FusedLocation 变更路径上**原地改写**坐标（HookUtils.java:934-954 突变真实 Location 对象，保留底层 mock 标志） | 双 spoofer 叠加，运维脆弱；依赖 hook 的突变路径而非构造路径 | 不改任何仓库，但工程上比 A 差 |

## 对 L1 实施的影响

无。L1 闸门按 spec v2.1 原样实施（mock-required 不动）。选项 A/B/D 都只影响"设备上如何产生可通过闸门的 spoof"，不影响闸门逻辑本身。L2 自动化（档案编辑 UI 驱动）待 operator 拍板后实施。

## 设备现场恢复

- hopefactory mock 已 Stop；caiyao `mock_location` appop 已还原 `default`（spike 期间临时设过 allow）；临时 db 副本已删；设备回到桌面。
