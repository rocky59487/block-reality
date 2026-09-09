# GAME_INPUT：模組原生輸入入口

2026-09-10，先於實作凍結。範圍是模組，不變更 tectonic2 或 BSI 契約。

## 輸入語意

新增獨立於舊 protocol 2 的世界快照；保留 revision、每格材料/斷面宣告、axis、axisRot、joint、
fill/strength 與 SI 載重。不由幾何猜軸、不在 Java 抽取模型。地面是六面相鄰的獨立 support 記錄，
去重、不得佔用結構座標；Forge 讀取可站立接觸面，純 Java 只整理已觀測資料。

隨模組攜帶 BSI vocab 宣告 JSON，機械數值為 D-012/既有 materialCatalogue 的 SI 轉寫，
不計算截面性質。steel/timber/rebar 是 member；plain concrete/brick 依 D-030 為 monolith；
三種 panel 各用獨立材料名稱承載厚度。既有 concrete/brick 截面 token 作遷移別名，
不再偽裝為固定窄梁；真正切遊戲時必須更新名稱、說明與存檔遷移。

ID 只取 `bsi.vocab.declare` 回覆（契約 vocab.response）；不假設宣告次序就是 ID。
用返回的 id→name 與 name→id 建立不可變表。名字不認得直接拒絕整個請求，不以 -1 偷代。
只有目錄明示 default/generated section 的 monolith/panel/support 可送 sect=-1。
重宣告開始即清除舊詞彙/世界有效性；失敗不得沿用舊世界或舊名字。

## 固定 gates

| ID | 驗收與 oracle |
|---|---|
| GI-1 | 任意非連續/重排合法 ID 仍映射正確；缺欄位、非整數/負/越界 ID、重複 ID/名稱、錯 method/kind/revision/version 拒絕，不猜值 |
| GI-2 | 九種既有方塊宣告皆有唯一 mapping，panel 厚度 .15/.20/.02 m；鋼 E=2e11 Pa、rho=7850、sigmaC=350e6；斷面以米宣告，無 A/I/J 手算 |
| GI-3 | 純快照與映射表不可變；三軸/四 rotation/兩 joint/fill/strength/64-bit revision 與 SI force 逐值保留；不合法/未知宣告在送入前拒絕 |
| GI-4 | 六面 ground 去重；與結構重疊、非相鄰觀測、重複結構拒絕；規範座標序；鏡像/旋轉保持相同接觸集合。不讀不存在的地面 |
| GI-5 | 真 `InProcessEngine` 宣告此完整 vocab 並分析鋼懸臂；原始回覆與入口同 revision 的全域旗標/站點/幾何/每島結果保持一致。附帶 re-declare 失敗與舊世界失效驗證 |
| GI-6 | 具名故障注入：ID 換成宣告序（GI-1）；Y/Z 軸交换（GI-3）；忽略 ground（GI-4）。每臂須編譯成功且指定測試斷言 FAIL，編譯錯不算 |

真引擎不可用可 SKIP，但須逐項列出；不視為已驗遊戲換裝。GI-5 使用現有開發庫而非發布資產，
不新增效能/雙平台聲稱。此單元不改判準 N25，也不把舊 Sidecar 測試當新入口證據。

GI-6 固定反例（執行前）：每臂只跑具名一項，1 registered/1 FAIL，且需有 AssertionFailedError。
ID_ORDER → BsiVocabularyTest.usesReturnedIdsInsteadOfDeclarationOrder；
SWAP_YZ → GameWorldSnapshotTest.preservesDeclaredAxesRotationJointAndSiValues；
DROP_GROUND → GameWorldSnapshotTest.includesAllSixObservedContactsOnceInCanonicalOrder。
首次 unit 啟動在 compileTestJava 發現測試少傳 BsiFrame.decode 的 length，引數已修；
沒有測試執行，不計作故障臂或產品 FAIL。
