# 引擎發現

接 FrameCore v4.0.0 過程中量到的兩件事。兩件都**只在非正方形斷面上可見**，而 `GATES.md` 的第一條 fixture 硬規則（第一批 fixture 全部用非正方形斷面）在第一次跑就抓到它們。

> 這條規則不是謹慎，是**前身踩過兩次的坑**（`PFSF-CORE` #71、ArchSim AS-72-u2 的 review NIT）。
> 兩次都是綠燈掩蓋座標系錯誤。這次它立刻付了利息。

兩件都**不影響 FrameCore 的 D/C 判定**——`ElasticAllowable`、`Section::Wz()` 與撓度三者互相一致且正確。受影響的是**應力場的視覺化資料**，而那正是應力眼鏡要畫的東西。

---

## 發現 1 · `memberFiberSigma` 的軸配對與其餘引擎不一致

### 量測

```
Section::Rectangular(b=200, d=400)
  A  = 80000        Iy = 2.66667e8    Iz = 1.06667e9
  cy = 100          cz = 200          Wy = 2.66667e6    Wz = 5.33333e6
```

`(Iy, cy)` 與 `(Iz, cz)` 是**互相配對**的——`Wz = Iz/cz` 成立，且是課本的 `b·d²/6`。

但 `StressKernel::memberFiberSigma` 算的是：

```cpp
case MemberFiber::TopY:   return sN + Mz * ( cy) / safeIz;   // cy 配 Iz
case MemberFiber::PlusZ:  return sN + My * ( cz) / safeIy;   // cz 配 Iy
```

以 `Mz = 1e8` 實測：

| | 值 |
|---|---|
| `memberFiberSigma(TopY)` | **9.375** |
| `memberCornerSigmaMax` → `M/Wz` | **18.75** |

**剛好 2 倍**（`cz/cy = 200/100`）。

### 哪一個對

用一個與命名無關的物理量裁決——**撓度**。懸臂 `L=4000`、`P=20000`、`E=200000`、`rho=0`：

```
tip deflection      = 2.00000 mm
P L^3 / (3 E Iz)    = 2.00000    rel = 2.220e-16   ← 命中
P L^3 / (3 E Iy)    = 8.00000    rel = 7.500e-01
```

局部軸：`local y = (0,0,1)` = 上，`local z = (0,-1,0)` = 水平。載重沿 `local −y`，`Mz` 是唯一非零力矩。

**所以垂直方向的彎曲由 `Iz` 主導，極纖維距離是 `cz`。** `ElasticAllowable` 的 `M/Wz = M·cz/Iz` **是對的**；`memberFiberSigma` 用 `cy` 配 `Iz` 少算一半。

### 為什麼一直沒被發現

`F1..F72` 的所有 fixture 都用 `Section::Rectangular(side, side)`——**正方形，`cy == cz`，差異在數值上不存在**。

ArchSim 的 review 已經注意到這個形狀並標為 dormant：

> `Cy/Cz` pairing was swapped vs the engine's `Wy=Iy/cy` convention (dormant — every v2 envelope is square — but **the first non-square section would mis-scale weak-axis D/C 2x**)

而 D-004（斷面與方塊尺寸解耦）讓非正方形斷面成為**常態**，所以它必然會醒來。

### 本專案的處理

sidecar **不使用** `memberFiberSigma`，自己以配對正確的 `(Mz, cz, Iz)` / `(My, cy, Iy)` 計算，並由 `verify.py` C1c 對 `M·c/I` 釘住（實測 rel = 1.47e-16）。

---

## 發現 2 · `computeStressField` 的分布載重曲率項符號相反

### 量測

同一根懸臂（`L=4000`、tip `P=20000`、自重 `w=6.161 N/mm`），`computeStressField(model, sr, 11)` 的逐站上緣應力：

```
x=0     24.2410      ← 正確（等於 M_i / Wz）
x=2000   5.1897
x=4000 -18.4820      ← 自由端，應為 0
```

而同一次求解的**構件端力**是：

```
end i  Mz = 1.29285e8      end j  Mz = 0
```

`end j Mz = 0` 是對的——自由端沒有外加力矩，內力矩必為零。

### 診斷

逐站的二階差分是常數（−0.19），所以剖面確實是拋物線，只是端點跑掉。手算：

```
線性項   M_i − V_i·x        = 1.29285e8 − 44642.72×4000 = −4.928e7
UDL 項   + w·x²/2           = +6.161×16e6/2             = +4.929e7
                                                          ─────────
正確的 M(L)                                                    ≈ 0  ✓

若 UDL 項取負                −4.928e7 − 4.929e7 = −9.857e7
換算應力                     −9.857e7 / 5.3333e6 = −18.48 MPa  ← 與實測吻合
```

**所以曲率項的符號相反。** 端力正確，只有取樣重建錯。

### 本專案的處理

sidecar **不使用** `computeStressField`，改用課本疊加從已驗證的端力重建：

```
M(x) = M_i·(1−t) + M_j·t + (w/2)·x·(L−x)          t = x/L
```

——兩個已驗證端點之間的直線，加上均布載重貢獻的拋物線，該拋物線**在兩端由構造為零**。剪力在均布載重下是線性，所以內插是精確的。

實測全部 11 站對閉合解 `σ(x) = [P(L−x) + w(L−x)²/2] / W`：

```
 x(mm)     sigma_top     閉合解        rel
     0      24.24102     24.24102     1.47e-16
   400      20.98523     20.98523     1.69e-16
   800      17.91425     17.91425     0.00e+00
  1200      15.02810     15.02810     0.00e+00
  1600      12.32677     12.32677     1.44e-16
  2000       9.81025      9.81025     0.00e+00
  2400       7.47856      7.47856     0.00e+00
  2800       5.33169      5.33169     1.67e-16
  3200       3.36964      3.36964     1.32e-16
  3600       1.59241      1.59241     1.39e-16
  4000       0.00000      0.00000     0.00e+00
```

---

## 發現 3 · 兩端支承、無內部節點的構件沒有自由度

**這一條不是 FrameCore 的問題，是我們這側擷取的限制**，但它是在 v4 上量到的，放在一起。

寫 issue #14 的迴歸 fixture（簡支梁自重、跨中控制）時撞到：支承是 `fixAll`，而擷取只在
run 端點與交會處產生節點。所以「兩端都支承的一根梁」在模型上是**兩個節點、兩個都完全
固定**——自由度是零，引擎正確地回報：

```
singular: true
diagnostic: "fully constrained (no free DOF)"
```

已記在 `sidecar/README.md` 的已知邊界。fixture 改用「向上端點荷載的懸臂」製造內部控制
斷面（`P = wL/2` 讓兩端彎矩恰為零、跨中 `wL²/8`），而不是繞過這個限制。

修對它需要在構件內部產生節點，與 issue #14 的「荷載點必須切出節點」是同一件事。

---

## 誠實邊界

- 前兩件都是**在 v4.0.0 這個凍結版本上量到的**，未回報上游，也未修改 FrameCore 原始碼——sidecar 只是不走那兩條路徑。
- 發現 1 的裁決依據是撓度（`rel = 2.2e-16`），這是與命名慣例無關的物理量。
- 發現 2 的裁決依據是自由端內力矩必為零，以及端力自身的一致性。
- **`ElasticAllowable` 的 D/C 未受影響**，`verify.py` C1 的 D/C 手算對照仍然成立。
- 未檢查 `My` 方向（`PLUS_Z` / `MINUS_Z` 纖維）的符號——目前所有 fixture 都是單軸受彎。**雙軸受彎的 fixture 是下一輪要補的。**
- **end-J 的元素端作用量符號契約尚未凍結。** 目前所有 fixture 的 `M_j` 不是零就是由對稱
  決定，所以逐站內插用的「兩端同一 section convention」這個假設**在數值上還沒被檢驗過**
  （issue #15.3）。要檢驗它需要一個兩端彎矩都非零的 fixture，而發現 3 說明那需要先有
  內部節點。**這是下一刀第一件事。**
- 未檢查殼元素的應力場。

## 上一版的一處更正

這份文件的 11 站表格當時是**印出來的**，不是斷言出來的——`verify.py` 只核對根部與自由端。
數字沒有錯，但「全站機器精度」這個宣稱當時**沒有 gate 撐著**（issue #15.2）。
現在 `verify.py` 與 Java 端各有一個逐站斷言，實測 `worst rel = 1.69e-16`。

## 重現

```bash
python3 sidecar/verify.py sidecar/build/br-sidecar          # C1c/C1d 覆蓋發現 1、2 與中性軸符號
cd mod && gradle test -Dbr.sidecar=../sidecar/build/br-sidecar
```
