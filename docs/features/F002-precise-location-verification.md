---
feature_ids: [F002]
related_features: [F001, F003]
topics: [android, fake-gps, location-precision, verification]
doc_kind: spec
created: 2026-08-01
---

# F002: Precise Location Selection & Spoof Verification

> **Status**: spec | **Owner**: @kimi | **Reviewer**: @codex-sol | **Priority**: P1 (operator-declared: "聚焦地址的问题，这个很重要")
> **Upstream**: [issue #3](https://github.com/TERRYYYC/Faketest/issues/3) with 2026-08-02 device experiment conclusions

## Why

The operator's worklist coordinates are the business's core data, but the current Fake GPS app cannot select them: its search returns "no search results" for raw `lat,lng` and snaps the pin to the nearest geocoded address — unstable snaps observed ~0.9–1.5 km apart for the same requested point (issue #3 evidence: Khreshchatyk Street 22 / Spaska Street 30-А for 50.45N 30.5236E). Every CellRebel result is currently attributed to the *requested* coordinate while the *actual* spoofed coordinate is unverified and possibly far off.

Operator: "输入经纬度并不能直接选中我们需要的地址，这是一个问题……后续很有可能会去选择别的 app" — F002 must make precision verifiable regardless of which location app is used, and find a precise selection path.

## Verified facts (device experiments, moto g54 / Android 15, 2026-08-02)

1. Fake GPS (`com.hopefactory2021.fakegpslocation`) search does not resolve raw coordinates ("There are no search results"); pin snaps to nearest address, snap target unstable.
2. `dumpsys location` exposes last fixes per provider; real GPS fixes carry a `satellites` Bundle and hAcc≈1.5–4 m — real vs mock is machine-distinguishable.
3. The phone's real GPS is strong (25–27 satellites); an inactive mock means real location leaks into tests.
4. adb key-stream text input is mangled by the IME; accessibility `ACTION_SET_TEXT` is reliable (our app already uses it).
5. Our manifest still declares `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`.
6. A second fake-GPS app (`name.caiyao.fakegps`) is installed on the device — unexplored.

## What (two layers)

### L1 — Spoof verification (must-have, app-side, app-agnostic)

After the location stage reports activation (F003 semantics), our app reads its own `LocationManager` last-known location and verifies:

- the fix is a **mock** (no satellites Bundle / test provider where detectable), and
- `distance(actual, requested) ≤ tolerance` (default 100 m, configurable in Advanced).

Outcome: pass → proceed to CellRebel; fail → typed failure `LOCATION_MISMATCH` (or `LOCATION_NOT_MOCKED` when a real-GPS fix is detected), no quota consumed (F001 INV-10 lineage). Records actual coordinate + distance in the attempt audit (`stage_notes` or new audit fields — decide in plan).

This works with ANY location app, including a future replacement — it verifies outcomes, not the app's internals.

### L2 — Precise selection path (research spike → implementation)

Time-boxed spike, output is a decision:

1. Long-press map pin placement in the current Fake GPS app (bypasses geocoding snap): verify via tree dumps whether pin = exact coordinate.
2. Favorites/saved-locations flow with exact coordinates.
3. Evaluate `name.caiyao.fakegps` for direct coordinate entry.
4. Decision record: which path (or which replacement app) becomes the F002 selection implementation; if none is precise, L1 tolerance becomes the enforcing gate and the imprecision is surfaced per-attempt.

## Acceptance Criteria

- [ ] AC-F2-1: After every location-stage activation, the app verifies mock-ness and coordinate tolerance before proceeding; failures are typed and never consume quota. Unit tests with fake location providers + a device verification run.
- [ ] AC-F2-2: Attempt audit (History + export) exposes the actual verified coordinate and distance error for each attempt.
- [ ] AC-F2-3: L2 spike produces a written decision (selected precise-input path or replacement app), with tree-dump evidence.
- [ ] AC-F2-4: If a precise path is implemented: device run shows ≥9/10 attempts within tolerance at non-addressable coordinates (e.g. park/river points where geocoding snap would be large).

## Open Questions

| # | 问题 | 分类 | 状态 |
|---|------|------|------|
| OQ-F2-1 | Tolerance default (100 m proposed) and whether it is per-plan or global | technical | ⬜ plan phase, default global Advanced param |
| OQ-F2-2 | Which precise selection path wins (long-press / favorites / caiyao app / other) | technical | ⬜ L2 spike |
| OQ-F2-3 | Mock detection reliability on Android 15 (`Location.isMock` deprecated semantics vs provider heuristics) | technical | ⬜ verify on device |

## Non-goals

- Replacing the whole scheduling/pipeline (F001/F003 stable).
- Supporting multiple simultaneous location apps.
- Geocoding/reverse-geocoding features inside our app.

## Timeline

| 日期 | 事件 |
|------|------|
| 2026-08-01 | Operator declared address precision the top priority; issue #3 filed |
| 2026-08-02 | Device experiments confirmed root cause (no raw-coordinate search; address snap ~1 km) and the dumpsys verification channel; F002 spec drafted |
