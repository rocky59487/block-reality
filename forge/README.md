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

每根 member 11 站 × 4 條纖維。每條纖維帶自己的**世界方向**與**拉為正**的應力，
所以渲染端不做符號轉換、不做座標轉換、不做正規化、不做色階查表——那些都在上游做過一次且有測試。

- **上緣受拉 → 藍**、**下緣受壓 → 紅**（`StressPalette.SIGNED_DEFAULT`）
- 飽和度**線性**對應應力，所以 HUD 的滿刻度數字能把顏色讀回 MPa
- **中性軸**畫成灰線；某一站整個斷面同號時**斷開**，因為那裡真的沒有中性軸
- 顏色之外還有 hatch（`Hatch`），不單靠紅綠
- 整個結構共用一個刻度，所以 member 之間可比

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

## 🔴 誠實邊界

**這個沙箱沒有 Minecraft，也沒有顯示卡。Forge 這半從未被編譯過，更沒有被執行過。**

- 純 Java 半（`../mod`）**有** 60 個測試在跑，其中 14 個實際驅動真的 FrameCore 二進位檔
- Forge 半的正確性目前**只有程式碼層面的論證**：API 用法、事件時機、dist 分離、封包安全
- 「應力渲染正確」在**資料層**是被測試釘住的（顏色、位置、符號、梯度、中性軸），
  但**畫面上長什麼樣沒有人看過**
- 第一次 `runClient` 要當成**未驗證程式碼的首次執行**，不是回歸測試

最可能出錯的地方，按可能性排序：

1. `RenderLevelStageEvent` 的 `PoseStack` 與相機位移——世界空間對不上會整條偏移
2. `RenderGuiOverlayEvent.Post` + `VanillaGuiOverlay.HOTBAR` 的 overlay type
3. `NetworkRegistry.newSimpleChannel` 在 47.x 的 deprecation
4. `BlockEvent.EntityPlaceEvent` 取得 `ServerLevel` 的 cast

## 還沒做（Demo v0 驗收清單剩下的）

`API_ARCHITECTURE.md` §7 有 13 項。目前這一刀做完前 5 項與第 12、13 項；
斷裂 → 剛體 → 掉落 → 撿回 → 重裝 → 損傷持久化（第 6–11 項）**未實作**——
那需要 member registry（D-011）與存檔格式，是下一刀。
