# API Mod 架構

目標：**先釘骨架，讓玩法可以一直加而不用回頭改。**

三條原則，其餘全是推論：

1. **API 不含實作** — 只有介面、資料型別、事件。任何人 compile against 它，碰不到求解器
2. **世界只有實作層能改** — 第三方可以「看」和「演」，不能寫世界。它壞掉最多是特效沒了
3. **加東西不用改舊東西** — 新材料、斷面、工法、掃描模式都走註冊表

---

## 1. 分層

```
┌─ 內容層（可以有很多個 mod）──────────────────────┐
│  方塊、物品、工法包、材料包、關卡                    │
│  只依賴 api 的註冊表                                │
├─ blockreality-api ────────────────────────────────┤
│  資料型別   MemberId / MemberSnapshot / DamageRecord │
│            WorldRevision / FailureEvent / Utilization│
│  註冊表     材料、斷面、工法階段、掃描模式             │
│  SPI       特效處理器                                │
│  事件       分析前後、失效、member 生命週期            │
│  ★ 零實作、零世界寫入                                │
├─ blockreality（實作）──────────────────────────────┤
│  member registry（持久、per-dimension）              │
│  快照 → 背景分析 → 回寫                              │
│  sidecar 橋接、網路、存檔                            │
│  ★ 唯一能寫世界的地方                                │
├─ sidecar 程序（獨立 process）────────────────────────┤
│  FrameCore v4 · 6-DOF 樑柱 + MITC4 殼 + D/C          │
└──────────────────────────────────────────────────┘
```

### API / 實作的邊界怎麼守

**一個 repo、兩個 source set**（`api` 與 `main`），`main` 依賴 `api`，反向禁止。

建置期檢查兩條（借 PFSF-CORE #91 EPIC 的 forbidden-import gate）：

1. `api` source set **不得 import 任何 `com.blockreality.impl.*`**
2. `api` source set **不得出現世界寫入呼叫**（`setBlock` / `destroyBlock` / `Level#addFreshEntity` …）

`api` 可以用 `BlockPos`、`ResourceKey<Level>` 這類 vanilla 值型別——那不可避免，也無害。

**先不拆成兩個 jar。** 紀律先建立，打包延後——早拆會讓重構變貴，而紀律用 gate 就能守。

---

## 2. 引擎跑在獨立 process（D-013）

### 為什麼

引擎是 C++。同 process 的話，**一次 segfault = 整個伺服器死 + 存檔可能損壞**。這對一個要給別人裝的 mod 是不可接受的失敗模式。

而分析本來就是背景跑的（D-008：建築尺度約 100ms），**IPC 溝通成本相對 100ms 可以忽略**。

### 協定

用 `frame_capi_v2` 既有的形狀，走 **stdio**（不用 port，沒有防火牆與埠衝突問題）：

```
12 byte 固定 header：'F' 'C' + FLAGS + LE u32 header 長度 + LE u32 payload 長度
header = JSON（加欄位不破 wire）
payload = raw little-endian double
```

- `frame_v2_abi_version()` 是**嚴格單調整數**，兩個 minor 版本的相容 SLA
- `transport.async`：送出非阻塞排隊 + 背景 worker → **天然配 tick 迴圈**

### 程序生命週期

| 時機 | 行為 |
|---|---|
| Mod 載入 | **不啟動**。sidecar 是 lazy 的 |
| 第一次分析請求 | 啟動 + 握手（版本協商）+ 快取能力清單 |
| 每次請求 | 有 timeout。逾時 → 殺掉 → 重啟 |
| 偵測到 crash | 指數退避重啟（2s / 4s / 8s / 16s），連續失敗 N 次後停用並提示 |
| 伺服器關閉 | 送 shutdown → 等待 → 逾時強制殺 |
| **防僵屍** | 子程序監看 stdin EOF；父程序死掉 → stdin 關閉 → 子程序自我了斷 |

### 🔴 失敗語意：全部 fail-safe，不 fail-open

| 情況 | 行為 |
|---|---|
| sidecar 沒安裝 | **Mod 正常載入**，分析功能停用，明確提示。**不是 crash** |
| 分析中 crash | 該次請求失敗，**member 狀態完全不變**，退避重啟 |
| 卡住 | timeout → 殺 → 重啟 |
| 版本不符 | **fail closed**，拒絕綁定，明確訊息（照抄 PFSF-CORE 的 ABI 三段鎖） |

**最重要的一條：分析失敗絕不改動世界。** 結果只在成功時套用。

### Backpressure

請求排隊快過 sidecar 服務速度時：

- **顯示軌可合併** — 同一結構的舊請求直接丟棄，只留最新的
- **承諾軌不可丟** — 排隊等待，必要時阻塞該結構的後續編輯

這是兩軌分離（D-007）的直接推論。

---

## 3. 第三方只能做特效（D-014）

### 契約

```java
public interface IFractureEffectHandler {
    /** 在 Block Reality 已經完成世界變更之後呼叫。回傳值被忽略。 */
    void onCrushing(CrushingDescription desc);
}

public record CrushingDescription(
    WorldRevision revision,
    ResourceKey<Level> dimension,
    AABB region,
    Vec3 direction,                 // 失效法向
    float severity,                 // 0..1
    List<BlockSnapshot> visualSource, // 變更前的外觀，供粒子/mesh 用
    long transactionId
) {}
```

三個關鍵：

1. **在世界變更之後才呼叫** — 它拿到的是「已經發生的事的描述」
2. **回傳值被忽略** — 它沒有辦法影響任何判定
3. **例外被捕捉** — 丟例外 → 記 log、停用該 handler、退回內建實作。**它不能讓求解崩掉**

### 為什麼這樣夠用

Issue #2 已經定了：**壓碎碎片是純視覺**——不造成傷害、不成為持久物件、不可回收、不污染存檔。

所以 handler 本來就只需要一份描述。**權限剛好等於需求，沒有多給。**

### 斷裂不走 handler

`FRACTURE` 產生的是**持久的剛體 member**（有傷害、可破壞、可重裝，Issue #3 的完整生命週期）。那是 Block Reality 自己的東西，不外包。

> 分工：**`CRUSHING` = 借外部特效；`FRACTURE` = 自己的玩法。**

---

## 4. 穩定性四條（全部來自舊倉庫踩過的坑）

| 坑 | 出處 | 對策 |
|---|---|---|
| 全域 static singleton → 多維度混在一張 map、無法單元測試 | `AUDIT_REPORT` P3 | **所有狀態 per-dimension**，key 是 `ResourceKey<Level>` |
| 全部塞進一個 `ServerTickEvent.END`，無跨 tick 排程 → 超過 50ms 直接掉 TPS | `AUDIT_REPORT` 2-7 | **tick 預算 + 跨 tick 排程**，做不完排下一 tick |
| 封包 `decode` 對越界拋例外 → **直接踢玩家** | `FdActionPacket` | **越界一律退化成安全值**，永不拋。照抄 `HologramSyncPacket` 的「先讀完所有欄位再判斷」 |
| 批次放置沒 post `EntityPlaceEvent`、沒檢查 `WorldBorder` → **繞過所有領地保護** | `AUDIT_REPORT` #9 🔴 | 每一格都走 vanilla 事件 + 檢查邊界 |

再加兩條新的：

**5. SPI 呼叫全部包 try-catch。** 第三方 mod 是不可信輸入。

**6. 網路節流。** 舊倉庫**完全沒有**（grep `rate`/`throttle`/`cooldown` 零命中），而 `PastePreviewSyncPacket` 最壞是 650 KB 單包。新版每個 C→S 封包都有速率上限與大小上限。

---

## 5. 效能

### 三段式（舊倉庫已驗證，直接搬）

```
主線程擷取不可變快照 → 背景執行緒分析 → 主線程回寫
```

- 執行緒池是**有界的自訂池**，**明確禁止 `ForkJoinPool.commonPool()`**（會搶 GC 執行緒）
- 結果經 `ServerLevel.execute()` 回主線程
- 快照不可變 → 分析期間玩家繼續編輯不會競態，只是結果會被 `worldRevision` 判為過期

### 預算

| 項目 | 預算 |
|---|---|
| Minecraft tick 總額 | 50 ms |
| **結構分析（主線程部分）** | **8 ms**，可配置 |

背景分析不佔這 8ms——8ms 是擷取快照 + 回寫結果的額度。

### LOD

- 取所有玩家到結構的**最小距離**（只要有一個玩家靠近就給高精度）
- `< 32 格` 1.0× / `32–96` 0.5× / `96–256` 0.25× / `> 256` 休眠
- **靜止的結構不重解**（舊資料觀察：任一時刻 80–95% 的結構是靜止的）

---

## 6. 擴展點（能加什麼）

| 擴展點 | 形式 | 加什麼 |
|---|---|---|
| 材料 | 註冊表 + JSON | 新材料等級、新規範來源 |
| 斷面 | 註冊表 + JSON | H 型鋼目錄、自訂斷面 |
| 工法階段 | 註冊表 | 新工序（如預鑄、後拉預力） |
| 掃描模式 | 註冊表 | 應力眼鏡的新鏡頭 |
| 特效 | SPI | 第三方碎裂 mod |
| 觀察 | Forge 事件 | 分析前後、失效、member 生命週期 |

### ⚠️ 刻意**不**開放的（同樣重要）

| 項目 | 理由 |
|---|---|
| 求解器 | 一個引擎，換實作走設定檔不走 SPI。多引擎並存會讓結果不可比 |
| 構件擷取規則 | 它定義了「什麼是結構」，開放等於放棄語意一致性 |
| wire 協定 | 版本化演進，不是擴展點 |

**明確列出不可擴展的東西，跟列出可擴展的一樣重要**——它防止 API 表面無限長大。

---

## 7. Demo v0 — 一根懸臂梁的垂直切片

**不是功能清單，是一條打穿所有層的線。** 一種材料、一種失效模式。

```
放鋼骨 → 出現 member → 加載 → 算出 D/C → 超載 → 斷裂
      → 掉落成剛體 → 撿回 → 重裝（帶著損傷）→ 存檔重開還在
```

### 驗收清單

- [ ] 放一列鋼骨方塊 → 產生**一根** member（不是 N 根），有穩定 ID
- [ ] member 拿到**非正方形斷面**（`GATES.md` 硬規則）
- [ ] 底部接地 → 支承條件成立；不接地 → **明確報「這是機構不是結構」**
- [ ] sidecar 啟動、握手、算出 per-member `D/C` 與六個內力分量
- [ ] 應力眼鏡 `UTILIZATION` 模式顯示利用率 + 圖例 + 主導模式
- [ ] `D/C > 1` → 發出 `FRACTURE` 事件（帶 `worldRevision`、位置、法向、嚴重度）
- [ ] member 進入 `DETACHING → DYNAMIC`，掉落、碰撞、翻滾至 `RESTING`
- [ ] 撞擊造成一次**有上限、有冷卻**的傷害
- [ ] 靜止 member 可撿回、可重新安裝
- [ ] 重裝**保留損傷**，以受損狀態重新分析，結果與全新 member **可觀察地不同**
- [ ] 存檔 → 重開 → member registry、ID、損傷全部還在
- [ ] **sidecar 殺掉 → 遊戲照常跑**，分析停用並提示，重啟後恢復
- [ ] 過期 `worldRevision` 的結果被拒絕，不覆蓋新狀態

### 這條線一次驗證了什麼

member 建立與身分（D-011）· 斷面解耦（D-004）· 引擎邊界（D-006）· 兩軌（D-007）· 失效交接 · 生命週期 · 損傷持久化 · sidecar 失敗語意 · `worldRevision` 閘門

**壓碎需要混凝土與工法鏈，是第二刀。**

---

## 8. 相關決策

`DECISIONS.md` D-013（sidecar）、D-014（第三方只能做特效）。
其餘全部是既有決策的推論，未新增前提。
