# Block Reality 結構 sidecar

獨立程序（D-013），JSON-lines over stdio。Minecraft 側送方塊與材料，**這裡擁有結構模型**——構件擷取、節點管理、求解、D/C 回收都在這一側（D-006）。

## 建置

```bash
sudo apt-get install -y libeigen3-dev cmake g++

cmake -S sidecar -B sidecar/build -DCMAKE_BUILD_TYPE=Release -DBR_STATIC_RUNTIME=ON \
      -DFRAMECORE_DIR=/path/to/architect_simulator/Plugins/FrameSolver/Source/FrameCore
cmake --build sidecar/build --parallel
```

FrameCore 以**原始碼**引用，不 vendor 進本倉庫。

**依賴只有 Eigen**（header-only）。FrameCore 的 supernodal lane 由 `FRAMECORE_SUPERNODAL`
在編譯期關掉（`-DBR_SUPERNODAL=ON` 可以開回來，那時才需要 METIS / OpenBLAS / LAPACKE）。

這不是降級：`SolveOptions::useSupernodalPrimary` 本來就是 `false`，每次求解走的一直都是
Eigen `SimplicialLDLT`。**實測開與關的輸出到最後一位都相同**，68 項 gate 兩邊全過。
少掉三個原生依賴換來的是：可以交叉編譯出一顆自足的 Windows 執行檔。

### Windows（在 Linux 上交叉編譯）

```bash
sudo apt-get install -y g++-mingw-w64-x86-64

cmake -S sidecar -B sidecar/build-win -DCMAKE_BUILD_TYPE=Release -DBR_STATIC_RUNTIME=ON \
      -DCMAKE_TOOLCHAIN_FILE=$PWD/sidecar/toolchain-mingw64.cmake \
      -DFRAMECORE_DIR=/path/to/Plugins/FrameSolver/Source/FrameCore
cmake --build sidecar/build-win --parallel
```

產出的 `br-sidecar.exe` 只 import `KERNEL32.dll` 與 `msvcrt.dll`——兩個都是系統的，
沒有要附帶的 runtime DLL。**Wine 實測 68 項全過，數字與 Linux 版逐位元相同。**

## 驗證

```bash
python3 sidecar/verify.py sidecar/build/br-sidecar
```

68 項，全部對閉合解或不依賴求解器正確性的不變量。

## 協定

單位：FrameCore 內部是 **N / mm / MPa**，一個方塊 = 1000 mm。
軸向：Minecraft Y 朝上，FrameCore Z 朝上 → `FC.X = MC.x`、`FC.Y = MC.z`、`FC.Z = MC.y`。

```jsonc
// 握手
{"op":"hello"}
→ {"ok":true,"engine":"FrameCore","protocol":1,"materials":[...],"sections":[...]}

// 求解
{"op":"solve","revision":7,
 "blocks":[{"x":0,"y":64,"z":0,"mat":"steel","section":"steel_rect_200x400","support":true}],
 "loads":[{"x":4,"y":64,"z":0,"fy":-20000}]}
→ {"ok":true,"revision":7,"singular":false,"maxDC":0.069,"governing":1,
   "members":[{"id":1,"lengthMm":4000,"dc":0.069,
               "governingFibre":"CRUSH","governingStation":0,
               "i":{"N":..,"Vy":..,"Vz":..,"T":..,"My":..,"Mz":..},"j":{...},
               "blocks":[[0,64,0],[1,64,0],...]}],
   "unassigned":[]}

{"op":"bye"}   // 或直接關 stdin
```

## 已實作的規則

| 規則 | 出處 |
|---|---|
| 共線同材質 run → 一根 member | D-010 |
| 單一方塊 `L/h = 1` 不是梁 → 回報 `unassigned` | `MEMBER_SEMANTICS` §1 |
| 兩根 run 共用的方塊 → 節點，並在該處切斷 | D-010 §7.4 |
| 斷面目錄**全部非正方形** | `GATES.md` fixture 硬規則 |
| 材料值取自 `DefaultMaterial`，鋼 E 釘 200 GPa | D-012 |
| 奇異 → 只回診斷，不回數字 | 機構不是結構 |
| 壞輸入 → error line，永不 crash、永不靜默預設 | `ENGINE_BOUNDARY` fail-safe |
| **缺 mat / 缺 section / 未知 token / 非整數座標 / 重複座標 / 缺 revision / 非有限數 → 全部拒絕** | issue #18 |
| **落在構件中間、無法映射到節點的荷載 → 拒絕整筆請求，不丟棄** | issue #14 |
| **斷面改變 → 該處成為節點，構件分段但仍連續** | issue #13 |
| **D/C 取自沿構件的 stations（含彎矩解析極值），不只兩端** | issue #14 |
| stdin EOF → 自我了斷 | 防僵屍 |

## 已知 v0 邊界

- member 只沿 **3 個主軸**擷取（對角斜撐尚未支援）
- 節點在方塊**中心**，所以 `n` 格的分析跨度是 `(n−1) m`，兩端各半格未進模型。
  這個約定**尚未凍結**；幾何、材料量與質量守恆的完整處理見 issue #13
- **兩端都是支承、中間沒有節點的構件會回報「fully constrained（no free DOF）」**。
  支承是 `fixAll`，而節點只在 run 端點與交會處產生，所以兩節點構件的自由度是零。
  這是擷取的真實限制，寫在這裡而不是繞過去——C8 因此改用「向上端點荷載的懸臂」
  來製造內部控制斷面
- 只有點荷載與自重；無風、無活載、無載重組合。**落在構件中間的點荷載會被拒絕**，
  因為那裡沒有節點可以承接它
- 無殼元素（模板宣告 MITC4 是第二刀）
- 無損傷、無 member registry（D-011 的持久身分尚未實作）
- **member `id` 是每次求解重編的暫時索引，不是持久身分**，不可用於任何交易或損傷
  紀錄（issue #17）
- 純混凝土 run 目前仍會自行形成 member。D-010 說混凝土只修改鋼骨／鋼筋構成的複合
  斷面，不應自行宣告骨架——**尚未修正**，需要 RC 複合斷面才能做對（issue #13）

## `governingFibre` 的語意

它說的是**控制纖維**，不是荷載型別，**也不是產品失效事件**。`ElasticAllowable` 取五個
比值的 argmax，所以鋼（抗壓 350 < 抗拉 500）的純彎曲會回報 `CRUSH`——壓側先到極限。
同一支懸臂換成混凝土（抗拉 3 < 抗壓 30）就會翻成 `TENSION`。

**這比「BENDING」有用**：它直接告訴玩家斷面哪一側先撐不住。verify.py C1/C1b 兩案把這個行為釘住。

**這個欄位原本叫 `mode`**，而那個名字會誘導下游把鋼構件當成混凝土壓碎送去純視覺碎裂
（issue #16）。`failureType`（NONE / FRACTURE / CRUSHING / MECHANISM）與 `handoffType`
**目前刻意不輸出**——這裡沒有任何東西在決定它們，缺席比猜測安全。

## 斷面 token 為什麼改名

`steel_h400` → `steel_rect_200x400`、`rc_400x600` → `concrete_rect_400x600`。

它們是**實心矩形**，不是 H 型鋼，也不是 RC 複合斷面。同深度的 H 型鋼 `Iz`、`Iy`、自重
全都不同。一個宣稱引擎沒在解的斷面的 token 會**通過所有測試**，因為測試也用同一個
token（issue #13）。真正的 H 型鋼需要可追溯的 A/Iy/Iz/J/質量資料，RC 需要鋼＋混凝土
的複合斷面；在那之前，這些是誠實命名的 fixture 斷面。
