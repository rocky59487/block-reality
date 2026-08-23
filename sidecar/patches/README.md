# FrameCore patches

`br-sidecar` 以**原始碼**引用 FrameCore(`architect_simulator` 倉的
`Plugins/FrameSolver/Source/FrameCore`,tag `v4.0.0`)。v4.0.0 是上游宣告凍結的
長期錨點,而本專案在把 sidecar 收斂成「不自己算任何力學」的純轉接層時,需要
引擎修正三個量測到的缺陷、並補三個後處理 API。這些改動以 patch 形式隨倉攜帶,
直到上游合併為止——沒有它們,sidecar 無法編譯,`verify.py` 也不成立。

## 套用

```
cd /path/to/architect_simulator
git checkout v4.0.0
git am /path/to/block-reality/sidecar/patches/*.patch
```

之後照 `sidecar/README.md` 的建置指令,把 `FRAMECORE_DIR` 指向這份 checkout。

## 內容

| Patch | 內容 | 引擎 gate |
|---|---|---|
| 0001 | `memberFiberSigma` 的 cy/cz 配對修正(200×400 斷面原本回一半極纖應力);`computeStressField` 的 UDL 重建符號修正(自由端原本重建出 2wL 與 wL²,閉合解為 0)| F72、F73 |
| 0002 | 新 API:彎矩解析極值站(`includeMomentExtrema`)、中性軸欄位(`naY`/`naZ`)、固定邊支承彎矩還原(`recoverShellEdgeMoments`,8 元素下 39.7% 低 → 14.0% 低)| F74、F75、F76 |
| 0003 | fiber 場的物理號向釘定(向下載的懸臂,根部上緣必須讀出「拉」——之前 76 個 gate 全比量值,號向翻了也全過);中性軸的 round-off 門檻 | F77 |

三個缺陷都是「量值對、fixture 遮蔽」型:正方形斷面遮蔽 cy/cz 對調、無 UDL 的
fixture 遮蔽重建符號、純量值 gate 遮蔽號向。對應的 gate 各自用非正方形斷面、
自重閉合解、拉壓斷言把它們永久釘住。上游套用後 `frametest` 為 F1..F77 全過。

## 驗證

```
# FrameCore 自己的測試(套用 patch 後)
cmake -S Plugins/FrameSolver/Standalone -B build-frametest -DCMAKE_BUILD_TYPE=Release \
      -DEIGEN_DIR=/usr/include/eigen3 "-DCMAKE_CXX_FLAGS=-DFRAMECORE_SUPERNODAL=0"
cmake --build build-frametest --parallel && build-frametest/frametest

# 本專案的 251 項 gate(建出 sidecar 後)
python3 sidecar/verify.py <path-to-br-sidecar>
```
