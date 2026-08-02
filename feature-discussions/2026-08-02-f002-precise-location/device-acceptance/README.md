---
feature_ids: [F002]
topics: [android, device-acceptance, location-gate, evidence]
doc_kind: evidence
created: 2026-08-02
---

# F002 L1 设备验收证据（moto g54 5G, Android 15, `ZY22JHW9M4`）

> **Date**: 2026-08-02 15:31–15:58 EEST | **Build**: `feat/f002-precise-location` @ `3ee3199` (debug APK)
> **结论**: L1 闸门在真机端到端全部按 spec v2.1 行为：ACCEPT（mock + 0.046m 误差）、LOCATION_MISMATCH（1549m）、LOCATION_NOT_MOCKED（真实 GPS 泄漏）、崩溃窗口审计留存、UI 权限请求流。

## 实验设置

- hopefactory Fake GPS mock 固定在 `50.479954,30.522806`（其存储 pin，dumpsys 确认 `mock` 标志）。
- 清单 A（`f002_accept.csv`）：target = mock 坐标本身 → 预期闸门 ACCEPT。
- 清单 B（`f002_reject.csv`）：target = `50.468,30.534`（距 mock ~1.55 km）→ 预期 REJECT。
- 两轮均 Location stage OFF + CellRebel stage ON（FreshFix 模式，gps_skipped 标记）。
- 最后停掉 mock 重跑清单 B → 预期 NOT_MOCKED（真实 GPS 泄漏场景）。

## 证据

### 1. 运行时权限请求流（UI，spec "trigger the request flow"）

- 预先 `pm revoke` 两轮权限 → Plan 页点 Start → 系统权限弹窗出现（精确/大致位置二按钮）→ 选精确+使用时 → 自动化正常启动。（logcat `GrantPermissionsViewModel ... LOCATION_TWO_BUTTON_FINE_HIGHLIGHT` 15:35:11）

### 2. ACCEPT 路径（`cellrebel_attempts_20260802_155944.csv` row 2）

- attempt 2 `succeeded`：Web=9.0/Video=9.0，配额 +1。
- 审计列：`actual=50.47995359,30.52280601`，`error=0.0457m`，`fix_is_mock=true`，`fix_accuracy=0.01m`，`tolerance=100.0`，fix/verified 时间戳齐。
- 引擎日志：15:42:50 plan start → 闸门静默通过 → CellRebel 生命周期 → 15:43:27 quota complete → DONE。

### 3. LOCATION_MISMATCH 路径（rows 3–11）

- 9 次连续 `failed: LOCATION_MISMATCH`：actual 固定为 mock 坐标，`error=1549.0477m`，`fix_is_mock=true`，**零配额消耗**。
- 每次重试前 buffer gate 等待（5s 缓冲投影），可恢复失败语义正确。
- Run 页实时显示 `Last failure: attempt #N — LOCATION_MISMATCH (not counted, retried after buffer)`。

### 4. LOCATION_NOT_MOCKED 路径（rows 12–20，停 mock 后）

- 9 次连续 `failed: LOCATION_NOT_MOCKED`：actual = 真实 GPS `50.4846048,30.4037324`，`fix_is_mock=false`，`location_error_meters` 空（mock 检查先于距离检查，按设计）。
- spec 核心恐惧（"inactive mock silently leaks the real location"）被闸门拦下，设备实证。

### 5. 崩溃窗口审计留存（row 1，非计划内真实事件）

- 我在引擎运行中执行了 `adb shell uiautomator dump` —— uiautomator 注册 UiAutomation 会**拆毁其他 a11y 服务**：`AutomationSvc: Service destroyed`（logcat 15:35:14.862），引擎协程取消，attempt 1 落 `interrupted: INTERRUPTED`。
- **审计列在 interrupted 行上完整保留**（INV-F2-4 真机实证）：`error=0.0457m, mock=true`。
- 教训（记录给后续所有设备操作）：
  1. 引擎运行中禁止 `uiautomator dump`（观察用 logcat / screencap）。
  2. `am force-stop` 会让 Android 把 app 的 a11y 服务从 `enabled_accessibility_services` 剔除；恢复 = `settings put secure enabled_accessibility_services <component>:<既有列表>`。

### 6. 24 列导出

- `cellrebel_attempts_20260802_155944.csv`：16 + 8 尾列（`actual_latitude … tolerance_meters_used`），全部行（含 interrupted/failed）审计齐整。
- History UI 每行显示 `loc ±{err}m · mock={bool} · acc={acc}m · tol={tol}m @ {lng}, {lat}`（见 `f002_hist.png`）。

## 设备现场恢复

- hopefactory mock 已 Stop；a11y 服务已重新启用；我们的 app 定位权限为"精确+使用时"。
- 我们的 app DataStore 现状：Location stage OFF / CellRebel stage ON / buffer 5s / tolerance 100m（如需还原 F003 默认，把 Location stage 打回 ON、buffer 调回即可）。
- caiyao `mock_location` appop 已在 spike 后还原 `default`；设备 DB 里留有 plan #1（完成）与 plan #2（0/1，9+9 次失败记录）作为审计样本。
- Google 账号安全确认弹窗（"是您本人在尝试恢复账号吗？"）出现过一次，**未作答**，按 BACK 退出——可能与新安装/位置变动有关，请 operator 自行留意。

## 未覆盖（等 L2 拍板后补）

- AC-F2-4：非可寻址坐标 ≥9/10 在容差内（需 caiyao 精确路径）。
- AC-F2-5 完整对照：吸附点拒绝已有等价证据（本文件 §3 即"错误位置被拒"），精确路径接受待 L2。
- Location stage ON 的激活锚点路径（activation anchor）真机验证（当前 FakeGpsHandler 驱动 hopefactory 必然吸附被拒，锚点逻辑单测已覆盖）。
