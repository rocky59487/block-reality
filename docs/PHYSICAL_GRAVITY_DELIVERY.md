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

**尚未更新正式靜態native bundle或預設jar，不得當作可直接安裝的發布版。**
這條候選可透過既有明確native路徑配置載入相容測試库；舊1.5庫的contract hash不符會拒絕，
不能默默當physical。下一步沿既有NATIVE打包機制重建靜態雙庫、來源pin、完整依賴／license／
provenance，重新驗jar換装後才合併影響預設發布物的改動。引擎版本仍1.5.0，#94、碎塊、
壓碎／摩擦滾動／停止、非線性與完整v2未由此完成；本輪沒有真Forge視窗或FPS宣稱。
