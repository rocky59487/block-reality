# Java 側

兩半：`mod/`（純 Java，這裡可以建置與測試）與 `../forge/`（Forge 1.20.1 mod，要在有 Minecraft 工具鏈的機器上跑）。

```
mod/api    零依賴的資料型別、色階、SPI     ← 任何人可以 compile against
mod/core   sidecar client、協定、渲染資料   ← 依賴 api
../forge   方塊、事件、封包、渲染           ← 依賴 core，composite build 引入
```

依賴方向是**單向且由建置強制**的：`mod/` 完全不 import Minecraft（D-015），`:api:checkApiPurity` 會擋。

## 建置與測試

```bash
cd mod
gradle test                                            # 純單元測試
gradle test -Dbr.sidecar=../sidecar/build/br-sidecar   # 加上真引擎的端對端測試
gradle check                                           # 再跑 api 純度 gate
```

沒帶 `-Dbr.sidecar` 時，需要引擎的那 18 個測試會 **skip**（不是假裝通過）。

## 這裡驗證了什麼

| | |
|---|---|
| 協定 | 編碼形狀、錯誤行、revision 不符、截斷行、未知 enum |
| JSON | 畸形輸入、深度炸彈、非有限浮點、控制字元 |
| revision 閘門 | 過期不 commit、過期仍可畫但標記、機構、引擎失敗 |
| 色階 | 上拉藍下壓紅、線性、色盲備援、退化輸入 |
| ribbon | 每纖維一條、位置換算、梯度、無中性軸就不畫 |
| sidecar 生命週期 | 缺檔、協定不符、卡死、崩潰重啟、殘留回覆、關閉 |
| **端對端物理** | **握手、一根不是五根、上拉下壓、11 站對閉合解、自由端為零、機構、單方塊、超載、決定性、內部控制斷面、荷載無法映射即拒絕、未知斷面即拒絕、斷面改變分段但連續** |

最後一列是**實際啟動 `br-sidecar`、實際跑 FrameCore**，不是 mock。

### 端對端那條線釘住的數字

懸臂 `L = 4000 mm`、`P = 20 kN`、`steel_rect_200x400`（非正方形）：

```
σ_top(x) = [P(L−x) + w(L−x)²/2] / W        W = bd²/6 = 5.3333e6 mm³
                                            w = ρ·A·g = 6.1607 N/mm
```

11 站全部命中，容差 `1e-6` 相對。上緣受拉、下緣受壓、兩者等值反號、中性軸在形心、自由端恰為零。

第二條線釘住的是**內部控制斷面**——向上端點荷載 `P = wL/2` 讓兩端彎矩恰為零、跨中達
`wL²/8`。只看兩端的容量檢查會說這根梁沒受力，那是「靜默地報安全」，最危險的一種錯。

## Forge 側

```bash
cd forge
BR_SIDECAR=/abs/path/to/br-sidecar gradle runClient
```

**沙箱裡沒有 Minecraft，所以 Forge 那半沒有被執行過。** 見 `../forge/README.md` 的誠實邊界那節。
