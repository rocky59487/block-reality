# Block Reality 結構 sidecar

獨立程序（D-013）。控制通道是 stdio 上的 JSON-lines;數值走**共用記憶體零拷貝**傳輸
（D-019,雙方映射同一個檔,double 以 raw little-endian 過去,stdio 只剩門鈴）,JSON
`solve` 保留為 wire 契約、fallback 與除錯面。Minecraft 側送方塊與材料，**這裡擁有
結構模型**——構件擷取、節點管理都在這一側（D-006）;但**這裡不算任何力學**——每個
應力、內力、D/C、還原值都是 FrameCore 函式的回傳值（D-020）。

## 建置

```bash
sudo apt-get install -y libeigen3-dev cmake g++

# FrameCore v4.0.0 + 本倉攜帶的引擎修正（見 sidecar/patches/README.md）
cd /path/to/architect_simulator && git checkout v4.0.0 \
  && git am /path/to/block-reality/sidecar/patches/*.patch && cd -

cmake -S sidecar -B sidecar/build -DCMAKE_BUILD_TYPE=Release -DBR_STATIC_RUNTIME=ON \
      -DFRAMECORE_DIR=/path/to/architect_simulator/Plugins/FrameSolver/Source/FrameCore
cmake --build sidecar/build --parallel
```

FrameCore 以**原始碼**引用，不 vendor 進本倉庫。`sidecar/patches/` 是對上游
v4.0.0 的三個修正/擴充 commit（F72–F77 gate 隨附）,套不上就建不出來——這是刻意的,
缺了它們引擎的 stress field 帶著已量測的缺陷。

**依賴只有 Eigen**（header-only）。FrameCore 的 supernodal lane 由 `FRAMECORE_SUPERNODAL`
在編譯期關掉（`-DBR_SUPERNODAL=ON` 可以開回來，那時才需要 METIS / OpenBLAS / LAPACKE）。

這不是降級：`SolveOptions::useSupernodalPrimary` 本來就是 `false`，每次求解走的一直都是
Eigen `SimplicialLDLT`。**實測開與關的輸出到最後一位都相同**，151 項 gate 兩邊全過。
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
沒有要附帶的 runtime DLL。**Wine 實測 151 項全過，數字與 Linux 版逐位元相同。**

## 驗證

```bash
python3 sidecar/verify.py sidecar/build/br-sidecar
```

151 項，全部對閉合解或不依賴求解器正確性的不變量。

## 協定

單位：FrameCore 內部是 **N / mm / MPa**，一個方塊 = 1000 mm。
軸向：Minecraft Y 朝上，FrameCore Z 朝上 → `FC.X = MC.x`、`FC.Y = MC.z`、`FC.Z = MC.y`。

```jsonc
// 握手
{"op":"hello"}
→ {"ok":true,"engine":"FrameCore","protocol":1,"materials":[...],
   "sections":[...],                       // 樑斷面 token
   "plates":[{"id":"concrete_slab_200","t":200}]}   // 板 token，附厚度

// 求解
{"op":"solve","revision":7,
 "blocks":[{"x":0,"y":64,"z":0,"mat":"steel","section":"steel_rect_200x400","support":true}],
 "loads":[{"x":4,"y":64,"z":0,"fy":-20000}],
 "buckling":true}                          // 預設 true；false 可省下特徵值求解
→ {"ok":true,"revision":7,"singular":false,
   "islands":2,"singularIslands":1,        // 每一棟各自求解；singular = 至少有一棟是機構
   "equilibrium":{"applied":[..],"reaction":[..],"residual":3.9e-16},
   "maxDC":0.069,"governing":1,"governingKind":"member",   // member 與 shell 各自從 1 編號
   "bucklingFactor":3.42,                  // 線性挫屈的載重倍數；<=1 代表已經失穩
   "nodes":5,"dof":30,
   "members":[{"id":1,"lengthMm":4000,"dc":0.069,
               "governingFibre":"CRUSH","governingStation":0,
               "i":{"N":..,"Vy":..,"Vz":..,"T":..,"My":..,"Mz":..},"j":{...},
               "blocks":[[0,64,0],[1,64,0],...]}],
   "shells":[{"id":1,"plate":"concrete_slab_200","t":200,
              "dc":1.44,"dcRaw":1.12,"edgeRecovered":true,"face":"TOP","corner":0,
              "world":[[..],[..],[..],[..]],          // 四角節點，MC 世界 mm
              "ex":[..],"ey":[..],"n":[..],           // facet 局部座標（見下方警告）
              "N":{"xx":..,"yy":..,"xy":..},          // 膜力，N/mm
              "M":{"xx":..,"yy":..,"xy":..},          // 彎矩，N*mm/mm，元素中心
              "Q":{"x":..,"y":..},                    // 橫向剪力，回收但**不篩選**
              "Mc":[[..],[..],[..],[..]],             // 逐角彎矩，D/C 與等值圖用的那份
              "McRaw":[...],                          // 還原前的原值，僅在有還原時出現
              "vmTop":..,"vmBot":..}],
   "unassigned":[]}

{"op":"bye"}   // 或直接關 stdin
```

### 共用記憶體傳輸（shm layout 1）

```jsonc
// hello 多一個能力欄位（沒有它的舊 client 繼續講 JSON）
→ {"ok":true, ..., "shm":1}

// JVM 建好 scratch 檔並映射後,叫 sidecar 映射同一個檔
{"op":"shm.open","path":"/tmp/br-shm-1234.bin"}
→ {"ok":true,"op":"shm.open","bytes":4194304}

// 之後的求解:請求已在映射區裡,門鈴不載數值
{"op":"solve.shm","revision":7}
→ {"ok":true,"op":"solve.shm","revision":7,"bytes":132536}   // 回覆也在映射區裡
```

- 全部 little-endian;double 是 raw IEEE-754,**從不文字化**——傳輸從構造上不可能
  改變數值。JSON 路徑同時修到 17 位有效數字(無損下限;之前是 10 位,每個數值都被
  截到 ~1e-10 相對,evidence 把這當引擎誤差引用了兩版)
- 嚴格半雙工:一問一答,請求與回覆重用同一段記憶體,門鈴就是互斥
- 字串不過二進位 wire:材料/斷面 token 用 hello 清單的索引,governing fibre 用枚舉序號
- 失敗的 solve 在門鈴上以與 JSON 相同的 error line 拒絕,不寫映射區;回覆裝不下時
  大聲拒絕並附 grow 提示,JVM 放大檔案重試一次
- verify.py 的 T 系列 gate 押著兩傳輸**逐位元相同**;確切欄位佈局的權威定義是
  `main.cpp` 的 `encodeShmReply` 與 Java 端 `BinaryCodec`（兩者由 gate 互鎖）

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
| 板 token 的 2×2 方塊方陣 → 一片 MITC4 facet，節點在方塊中心 | D-016、D-005 |
| 板 token 與斷面 token 不重疊；認不得的 token 拒絕 | D-016 |
| 不成 facet 的板方塊（孤塊、單格寬帶、**疊兩層的實心**）→ `unassigned` | D-016 |
| run 可以**支承於**它撞上的板方塊，共用該節點 | D-016 |
| 連通分量各自求解；機構只屬於那一棟 | D-017 |
| 荷載落點在分割**之前**對全體節點驗過 | issue #14 |
| 固端邊的支承彎矩由內部元素中心外推還原，取較大者 | 發現 6 |
| **被施加荷載的方塊是節點**，run 在該處切開 | issue #14 |
| 荷載落在**完全不屬於任何元素**的方塊 → 拒絕整個請求 | issue #14 |
| 每一島跑線性挫屈，回報全世界最小的 λ_cr | D-018 |
| 殼的膜元素開 QM6 incompatible modes（面內受彎精確） | D-018 |

### ⚠️ `ex` / `ey` / `n` 在 Minecraft 空間裡是**左手系**

它們在**引擎裡**是右手正交系。Minecraft 的軸映射 `(x,y,z) → (x,z,y)` 交換兩軸，是一個
**反射**，所以同樣三個向量讀在 Minecraft 空間就滿足 `ex × ey = −n`。

拿它們把點投影到 facet 上完全沒問題（客戶端就是這樣用的）。**不要用它們的外積去重建法向。**
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
- 只有點荷載與自重；無風、無活載、無載重組合
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
