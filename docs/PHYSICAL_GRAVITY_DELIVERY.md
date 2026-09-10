# 原生物理自重候選

2026-09-11。引擎來源 2bd898185f2c0cb9dd929b380c0f90d43212f905，配對56個contract檔案逐位相同，
hash c5dfaa40c15355c7a0f29d6b1eb31c8fc125fde7c87233e7d5e691d00b9f7321。介面是可選massModel=physical／bsi.mass.physical；BSI wire與五個CAPI仍1，
adapter新增相容的ABI2帶長度入口，旧請求預設analysis不變。

Main配對分支只更新contract與source pin；既有遊戲1f5c7b3的薄接線位於
codex/native-physical-gravity，BsiHeaders／InProcessEngine新增MassModel參數，
GameWorldSnapshot的production analyze明確PHYSICAL。舊overloads保留；Java不計算物理自重。

Windows／Linux真JNA各235檢查，66frame×3逐位相同；遊戲生產Java入口root force符合
矩形材料幾何解析值。兩CLIENT可編譯反例（漏production選項／漏header欄位）具名FAIL。
引擎另有三箱79／8faults、真庫571、ABI四配對／guard／sanitizer。production core409PASS／
40SKIP，Main265PASS／40SKIP；既有sidecar／平台SKIP並未算通過。
首GameInputNativeTest把physical結果與analysis預設相比而FAIL，保留舊解析oracle並新增
physical oracle後通過，硬容差未改。原始證據在
[engine PGN evidence](https://github.com/rocky59487/tectonic2/tree/2bd898185f2c0cb9dd929b380c0f90d43212f905/gate/evidence/PHYSICAL_GRAVITY_NATIVE)。

## 靜態候選換裝

2026-09-11：原 NATIVE 打包鏈現已建立同源
`691651156881670d5ec67349b162dadbf81d1ce9` 的靜態雙庫，兩個consumer ref均釘此來源，
契約hash不變；staging明示candidate。引擎版本仍1.5.0，未覆寫正式release。

同一個Forge jar SHA256為 `e90453995d4213f7401c3c6ad255d3d5900065b87a1fdef0e5276dc35be9a745`。
Windows/Linux均通過162個完整request（原96＋PGN66）的direct/jar DET3、兩JVM同cache，
jar取出的庫執行production JNA235、原native Java20/0SKIP；jar前後hash不變。
來源10破壞＋legacy profile、4 guard removal、bundle9及禁止類別3反例均拒絕。
物理完整response來自同compiler/靜態依賴的typed與native，未跨backend冒稱bitwise相同。

兩倉CI已修正舊ABI2拒絕測試，編譯真正未知ABI3並核對版本拒絕原因；ABI2控制實測接受。
首次staging抓到CONTRACT_SHA256末尾CRLF；回復Git的LF，未更動pin或放寬比對。
引擎可追溯證據見
[PGP RESULTS](https://github.com/rocky59487/tectonic2/blob/82165e52bf9f672356bb5e8182702bd432e646d7/gate/evidence/PHYSICAL_GRAVITY_PACKAGE/RESULTS.md)。

候選jar已可供後續遊戲整合，正式預設發布物尚未替換。#94、持久碎塊、壓碎、
摩擦滾動／停止、非線性與完整v2仍開放；本單元沒有真Forge遊戲場景或FPS宣稱。
