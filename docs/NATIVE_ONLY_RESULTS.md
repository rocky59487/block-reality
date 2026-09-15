# NATIVE_ONLY_RESULTS — 2026-09-10

模組限定；從 #111 `746f4fe` 接續。判準先凍，再改正式來源。

目的：出貨 jar 不再包含舊力學公式或從結果數值自行生成判定的相容 API。
原生的面樣本、主應力、von Mises、neutral-axis intercept 與 verdict flags 是唯一來源。
繪圖所需的座標轉換、單位換算、有限值檢查、顏色映射及有界樣本插值保留，
不能以插值當新的工程計算或供給 commit 決策。

| gate | 必須通過 |
|---|---|
| NO-1 | 正式 `MemberSnapshot` / `ShellSnapshot` 不再持有 StressFieldSpec / ShellFieldSpec，無 legacyDisplay；AnalysisResult / member / shell 建構必須明給 verdict，不由 D/C 或 λ 重算 |
| NO-2 | StressFieldSpec、ShellFieldSpec、舊 ProtocolCodec / BinaryCodec 與 neutral-axis 重建只在 test/專用 fixtures；正常 jar 與正式 class 的依賴不得含它們或 testlegacy；不是只停止呼叫而繼續出貨 |
| NO-3 | 刪前/刪後固定梁、殼封包 bytes hash 一致；原生→快照→封包→解碼的 double、旗標、stations、geometry 與 channel 11 bootstrap 守門既有 gates 繼續通過。缺 display 不從 end forces 補造 |
| NO-4 | 舊 JSON/shm/閉合解對比與 field 原有斷言保留在測試專用模型，不刪斷言或用 SKIP 把退場改成綠。測試專用模型轉換成現行快照必須顯式呼叫 snapshot，不能從正式程式呼叫 |
| NO-5 | core check、Forge build、jar 內容/bytecode gate、Linux 交付 native 的實際 packet/recovery 腿通過；依賴缺失與 Windows native 未跑逐項照登。真客戶端仍屬 CM/N25，不以 headless 冒充 |

將既有 legacy 結果模型與物理 helper 保留在 `mod/core/src/testFixtures/java`，core 與
Forge **只在 test source set** 加入；舊通訊 codec 留在 core test。正式 jar 不載入或
封裝 fixture。允許開發中 API 移除 legacy constructor，網路 channel/schema 不變。

兩條故障臂必須實際咬合：把 legacy 類別塞回 jar 會被 artifact gate 拒絕；
新增一個正式 class 參照 testlegacy（即使不呼叫）亦被 bytecode 依賴 gate 拒絕。
故障臂必須是可解析的 jar/class，不能拿編譯失敗冒充。原生結果回覆旗標與數值相反的
既有 fixture 仍通過，證明模組不把數字重新當 verdict。

首敗原樣保存。上游引擎、contract/pin、native binary 都不修改；最新 release 封裝、
持久 registry、倒塌剛體通道與材質模型不因本單元而宣稱完成。
