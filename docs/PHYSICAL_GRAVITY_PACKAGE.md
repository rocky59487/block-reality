# PGN 靜態套件與消費者換裝

2026-09-11，包裝改動前獨立凍結。延續PHYSICAL_GRAVITY_NATIVE及既有
MC66A_NATIVE_PACKAGE／RELEASE_V1_3／NATIVE_CONSUMER，所有原輸格與硬線保持。
核心基線2bd898185f2c0cb9dd929b380c0f90d43212f905，consumer production基線
3086939f4f5064232bd4f8e92767142cbce25fc0；此單元不改力學核心。

[硬] 沿release_source／build_release／package_release／stage_release_natives，
不建第二套native載入或發布機制。乾淨Git來源以外部指定完整40字元commit與
SOURCE_MANIFEST釘死，編譯前記錄；兩平台同來源，hello buildSha為其7字元，version仍1.5.0。
與2bd8981的core／contract逐檔一致；若需要核心修正，先另凍判準，原包首敗保留。
候選來源可包含本判準及打包verifier修正；禁止使用待驗binary自行推得期望身份。
目前contract hash c5dfaa40c15355c7a0f29d6b1eb31c8fc125fde7c87233e7d5e691d00b9f7321。

[硬] 原兩個artifact gate的ABI1-only判準改為依隨包bsi_engine.h的唯一已知
BSI_ENGINE_ABI宣告查核。ABI2必須entry1／2有效且回傳的abi_version各自正確，0／3拒絕；
舊ABI1契約仍要求1有效、0／2拒絕。未知／缺少／重複宣告拒絕，不讀待驗庫猜期望值。
CAPI仍1、原六匯出／OS-only imports／LP64及METIS32硬線不改；不得放行外部BLAS runtime。
已有host／PGN negotiation fault保持。新增verifier具名反例：拿ABI1契約要求新庫、缺失及
未知ABI宣告、錯標返回prefix；控制有效，反例必須命中ABI檢查而非先死在其他guard。
原6種acceptance證據破壞、10種source-chain與9種bundle反例照原定入口維持。

[硬] 靜態雙庫各artifact、334 boundary／10 PASS語料＋C13原SKIP、C14及PGN571 DET3；
typed payload與同build／backend比較，不把MSVC動態库原始payload冒充MinGW的oracle。
若不同backend逐位不同，記錄差異並維持相同解析硬線，不修改PGN tolerance。
i9複製最終Windows binary及完整SHA，不在i9重建。原ASan／core gate沿已驗同源接續，
不重跑未變2690或宣稱新的效能勝負。

[硬] 同一個包含兩平台庫、原license與source provenance的候選jar；零sidecar executable。
兩平台真JNA235及原NativeJarProbe案例，新增五格柱／四門架／fill／移除放回／模式切換
的完整66-frame corpus；jar前後逐位、三輪、兩JVM同cache競爭。Java production analyze
物理模式必須實際執行，native-dependent測試不得SKIP；舊sidecar／平台SKIP另列。
資產、SOURCE_MANIFEST、外部SHA256SUMS、staged與jar逐層hash核對，預期來源在build前記錄。

候選依basis=candidate明示，版本與舊v1.5來源以commit／完整binary hash區分；不覆寫正式
tag／資產、不稱v2已完成。本單元完成是可驗的雙平台交付，不代表真實滾動、碎塊外觀、
非線性、壓碎、#94正式遊戲驗收或完整MASS；V2_DELIVERY與原性能／數值紅帳不變。
