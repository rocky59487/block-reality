# Forge 層（Demo v0）

Minecraft 1.20.1 / Forge 47.4.13 / Official Mappings。

```bash
BR_SIDECAR=/abs/path/to/br-sidecar gradle runClient
```

沒設 `BR_SIDECAR` 也能跑——mod 正常載入，分析停用，HUD 明說原因。這條路徑由 `mod` 的
`SidecarLifecycleTest.missingBinaryDisablesAnalysisAndLeavesTheGamePlayable` 實測。

## 玩法（demo 只有三個動作）

| 動作 | 結果 |
|---|---|
| 放**結構鋼**方塊 | 進入結構模型；共線的一排 → **一根** member |
| 拿**應力眼鏡**右鍵空氣 | 切換鏡頭：利用率 → 應力 → 材料 |
| **蹲下**右鍵結構鋼 | 切換 20 kN 測試荷載 |

支承規則：結構鋼下方是實心非結構方塊 → 接地。**這是 demo 用的粗糙規則**，
`MEMBER_SEMANTICS` Q6（接地語意與固定度）還沒裁決，所以它只寫在一個 method 裡。

## 應力鏡頭在畫什麼

**一次只有一根構件畫細節，其餘每根一條線一個顏色。**

第一版把每根構件的四條纖維帶同時畫出來。單獨一根梁時那很好懂；真的蓋一棟建築時
那是一團霧，回答不了任何問題——而且纖維離中心線只有 0.2 格，遠一點就糊成一片。

所以現在分兩層：

| 層 | 畫什麼 | 回答什麼 |
|---|---|---|
| **每根構件** | 沿軸線一條線，顏色 = D/C（冷 → 琥珀 → 朱紅） | 哪裡快壞了 |
| **你正在看的那根** | 四條纖維帶 + 中性軸 + HUD 斷面圖 | 這裡在發生什麼 |

線是 **billboard** 的（垂直於視線加寬），所以正對著構件看時不會消失。
超過 128 格不畫，超過 64 格不列入 focus。

### ⚠️ 「上拉下壓」不是通則

**懸臂**上凸 → 上緣受拉；**兩端支承的梁**下垂 → 上緣受**壓**。**兩個都對。**

上緣是拉還是壓不是梁的性質，是結構形式的結果——那正是這個工具要說的事。所以 HUD 的
斷面圖**用文字寫出「受拉／受壓」與數值**，不要讀者從顏色反推。純文字對照用
`/br section <id>`。

只用顏色而不寫字，會讓只看過懸臂的人得出「工具反了」的結論。這件事實際發生過。

### 斷面圖

一條垂直軸代表斷面深度也代表零應力；每一列往右（拉）或往左（壓）伸出，長度正比於
該深度的應力，所以純彎曲是三角形、加了軸力就變梯形。中性軸畫在**它實際所在的位置**，
而不是假設在形心。

- 飽和度**線性**對應應力，所以滿刻度數字能把顏色讀回 MPa
- 纖維帶用**該構件自己的峰值**正規化（跨構件比較已經由軸線的 D/C 顏色負責）
- 整個斷面同號時**不畫中性軸**，因為那裡真的沒有

`TEACHING_PORT` 的色票（受拉 teal `#0C6266`、受壓 `#315E80`）保留為 `StressPalette.TEACHING`。

## 安全與穩定（全部來自舊倉庫踩過的坑）

| 坑 | 這裡的對策 |
|---|---|
| 全域 static singleton 混維度 | 狀態 per-dimension，key 是 `ResourceKey<Level>` |
| 全塞一個 tick 事件 → 掉 TPS | 主線程 8 ms 預算；擷取快照 → 背景求解 → 回主線程套用 |
| `ForkJoinPool.commonPool()` 搶 GC 執行緒 | 專屬有界具名 daemon 池 |
| 封包 decode 拋例外 → **踢玩家** | 全部 clamp，永不拋；NaN/Inf 歸零 |
| 批次放置繞過領地保護 | **demo 沒有 C→S 封包**，玩家動作全走 vanilla 事件 |
| 伺服器載入 client 類別 → crash | `client/` 全部 `@OnlyIn(Dist.CLIENT)`；封包用 FQN 不 import |
| 靜默截斷 | 超過 64 根 member 會**寫 log** 說有幾根沒畫 |

## 已驗證 / 未驗證

### ✅ 伺服器端：**在真的 Minecraft 裡跑過**

沙箱裡開了 Forge 1.20.1 專用伺服器，用 RCON 驅動，實際擺方塊、實際跑 FrameCore。

| 測到的事 | 結果 |
|---|---|
| mod 載入、註冊、config、指令 | 無例外，log 裡 0 個 ERROR |
| sidecar 自動找到並啟動 | `sidecar ready: FrameCore protocol 1, 5 materials, 5 sections` |
| 5 格懸臂（自重） | 1 根構件、`L=4000mm`、**peak 9.24 MPa**、D/C 0.0264 |
| 25 格懸臂（自重） | **peak 332.68 MPa**、D/C 0.9505 |
| 全部落在石頭上 | `MECHANISM — fully constrained (no free DOF)` |
| 完全懸空 | `MECHANISM — rank-deficient stiffness` |
| 單獨一格 | `unassigned 1 blocks formed no member` |
| 拿掉支承再算 | 立刻翻成 MECHANISM |
| 關伺服器 | 乾淨結束，**沒有殘留 sidecar 程序** |

兩個應力值都可以手算對：`σ = wL²/2W`，`w = 6.1607 N/mm`、`W = 5.3333e6 mm³`
→ 4 m 給 9.241、24 m 給 332.68。**兩個都命中。**

### 🔴 客戶端渲染：**沒有人看過**

沙箱沒有顯示卡，`runClient` 跑不起來。所以：

- **編譯過了**（真的 Forge、真的 MC 1.20.1，只有 3 個 deprecation warning）
- 應力渲染在**資料層**被 74 個測試釘住（顏色、位置、符號、梯度、中性軸）
- 但**畫面上長什麼樣沒有人看過**

最可能出問題的地方，按可能性排序：

1. **看不到帶子** — 纖維在方塊**內部**（斷面 200×400 mm，離中心線只有 0.2 格），
   所以渲染刻意**關掉深度測試**。這是寫程式時發現的：不關的話整條會埋在不透明方塊裡，
   渲染完全正確但什麼都看不到
2. `RenderLevelStageEvent` 的相機位移沒對上 → 整條偏移
3. `RenderGuiOverlayEvent.Post` 的 overlay type → HUD 不出現

> 另一個同類問題已經修掉了：sidecar 原本把節點放在方塊**角落**（`x·1000`），
> 所以整個覆蓋層會偏半格。現在節點在方塊中心（`x·1000 + 500`）——這是均勻平移，
> 力學結果一位元都沒變，但畫的位置對了。

## 還沒做（Demo v0 驗收清單剩下的）

`API_ARCHITECTURE.md` §7 有 13 項。目前這一刀做完前 5 項與第 12、13 項；
斷裂 → 剛體 → 掉落 → 撿回 → 重裝 → 損傷持久化（第 6–11 項）**未實作**——
那需要 member registry（D-011）與存檔格式，是下一刀。
