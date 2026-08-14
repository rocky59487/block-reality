# Block Reality 結構 sidecar

獨立程序（D-013），JSON-lines over stdio。Minecraft 側送方塊與材料，**這裡擁有結構模型**——構件擷取、節點管理、求解、D/C 回收都在這一側（D-006）。

## 建置

```bash
sudo apt-get install -y libeigen3-dev libmetis-dev libopenblas-dev liblapacke-dev

cmake -S sidecar -B sidecar/build -DCMAKE_BUILD_TYPE=Release \
      -DFRAMECORE_DIR=/path/to/architect_simulator/Plugins/FrameSolver/Source/FrameCore
cmake --build sidecar/build --parallel
```

FrameCore 以**原始碼**引用，不 vendor 進本倉庫。

## 驗證

```bash
python3 sidecar/verify.py sidecar/build/br-sidecar
```

33 項，全部對閉合解或不依賴求解器正確性的不變量。

## 協定

單位：FrameCore 內部是 **N / mm / MPa**，一個方塊 = 1000 mm。
軸向：Minecraft Y 朝上，FrameCore Z 朝上 → `FC.X = MC.x`、`FC.Y = MC.z`、`FC.Z = MC.y`。

```jsonc
// 握手
{"op":"hello"}
→ {"ok":true,"engine":"FrameCore","protocol":1,"materials":[...],"sections":[...]}

// 求解
{"op":"solve","revision":7,
 "blocks":[{"x":0,"y":64,"z":0,"mat":"steel","section":"steel_h400","support":true}],
 "loads":[{"x":4,"y":64,"z":0,"fy":-20000}]}
→ {"ok":true,"revision":7,"singular":false,"maxDC":0.069,"governing":1,
   "members":[{"id":1,"lengthMm":4000,"dc":0.069,"mode":"CRUSH",
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
| stdin EOF → 自我了斷 | 防僵屍 |

## 已知 v0 邊界

- member 只沿 **3 個主軸**擷取（對角斜撐尚未支援）
- 節點在方塊**中心**，所以兩端各半格未進模型
- 只有點荷載與自重；無風、無活載、無載重組合
- 無殼元素（模板宣告 MITC4 是第二刀）
- 無損傷、無 member registry（D-011 的持久身分尚未實作）

## mode 欄位的語意

`mode` 說的是**控制纖維**，不是荷載型別。`ElasticAllowable` 取五個比值的 argmax，
所以鋼（抗壓 350 < 抗拉 500）的純彎曲會回報 `CRUSH`——壓側先到極限。
同一支懸臂換成混凝土（抗拉 3 < 抗壓 30）就會翻成 `TENSION`。

**這比「BENDING」有用**：它直接告訴玩家斷面哪一側先撐不住。verify.py C1/C1b 兩案把這個行為釘住。
