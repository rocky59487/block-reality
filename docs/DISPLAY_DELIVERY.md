# DISPLAY_DELIVERY：完整樣本的有界交付

2026-09-08，量測與實作前凍結。基線 engine4bc958e / consumer cb6436e；
堆疊在 MC64_FORWARD（PR31/97）之上，核心、BSI契約與v1.3資產不變。

## [硬] 責任與 oracle

1. 單一交付政策控制整個封包的 bytes、格與站點總數，保留原64梁/512殼上限。
   不把各元素65536的局部上限當整個更新的預算。收端在解析配置前拒絕超長frame，
   解析元素前扣除累積格/站點額度，拒絕巨大宣告數量、總數矛盾與重复身份。
2. 全元素為選取單位。保留的元素必須逐位保持全部格、站點/雙側、控制索引、幾何、
   力學數值與獨立flags；不重新取樣、不裁格、不重新計算判定。全域判定來自完整結果。
3. 控制元素先取得預算，其餘按原輸入次序選取，梁與殼共用額度；不按D/C重判排序。
   若單一控制元素已超額或來源缺該身份，完整省略它且傳遞原控制kind/id與未顯示旗標。
   原「永遠畫出控制元素」在有界傳輸下無法對任意大元素成立，例外明確改為此可觀測結果。
   不得讓空顯示列表洗成「未分析」或「全機構」；HUD仍顯示全域值、判定、總數與省略提示。
4. 使用既有Member/Shell codec作唯一元素格式。每次更新至多對候選元素編碼一次，
   多位玩家共用不可變已編碼元素payload；不為每位玩家重新走所有站點。
   超額元素不能先配置無界暫存buffer再檢查大小。原本分析結果與求解預算不在本單元內。
5. 升Forge channel10；舊兩端會拒絕版本不合。維持revision/維度/clear/withheld生命週期。
   舊Sidecar入口與真BSI均通過同一路徑；不改renderer力學或把f32冒充display求解軌。

## [暫] 首跑量測與數字線

固定語料：small=4梁(每根4站/32格)+4殼(每片4格)；
dense=64梁(每根64站/300格)+512殼(每片4格)；
oversize=1梁(4096站/65536格)加small，其為全域控制元素；
shell-heavy=512殼(每片256格)，末片為控制元素。
格座標含負數與大正數，站點保留雙側identity；所有fixture在基線/實作後相同。
記錄實際wire bytes、送出元素/格/站點數、buffer capacity、每更新prepare/encode/decode
耗時與ThreadMXBean當前執行緒配置bytes（不是RSS或峰值heap），三輪保存原始輸出。
本機AMD數字一律RECORDED_ONLY，不作速度勝出/FPS宣稱；i9可用則同建置重跑。
量到基線後、實作前dated釘死封包/格/站點額度與預期保留集合；不事後換fixture救結果。

## [硬] 驗收

- 精確邊界：預算內/外一byte、格/站點累積恰好與+1；控制元素在尾端仍優先。
- 單一控制元素超額時傳回原summary與明示省略；混合機構不可因展示皆省略變成全機構。
- 全量較小包、部分包、空展示包均往返；原始global flags/f64與保留元素全內容不變。
- same build DET×3逐位；主線已有的任意截短、非法數值、未知狀態、過期revision測試保留。
- 配置上限以bounded buffer與累積counts為硬oracle，ThreadMXBean數字另列實測，不混稱。
- 隔離變異至少覆蓋：關閉byte限制、累積counts改單元素、丟控制優先、隱藏控制省略、
  收端放過超長frame、收端不扣累積額度；正常編譯後必須由具名斷言咬合。
- core/Forge全套與真v1.3 JNA回歸、API純度、契約/文件/任務帳守門。
  判準/失敗/修訂照登；stdout/stderr/XML原始bytes+SHA保存，核對staged/committed來源。

本單元是headless顯示交付，不是GAME_SWAP或整體FPS；NATIVE封裝、MC66屈曲、
DISPLAY_BAND(PE3依賴)及v2/v3/v4仍待原判準驗收。歷史41.7ms FAIL與Linux7FAIL保留。

## 2026-09-08 基線 harvest 與數字線（實作前）

AMD Ryzen9 8940HX / Windows11 / Java21.0.11，BLAS環境Haswell/4（本probe不求解）。
三輪相同wire SHA：small6036B；dense905978B；oversize1104013B；shell-heavy1546682B。
後兩者輸出buffer擴到2097152B，每次prepare+encode+decode配置約9.62/9.81MB。
這是執行緒累計配置而非峰值；原始輸出保留。未設定速度勝出線。

現在釘死：frame最多262144B，其中2048B保留summary/計數/控制身份，
元素payload合計最多260096B；格合計16384、站點合計2048，保持完整元素。
每更新最多嘗試64梁/512殼候選（控制元素先計入），避免超額後持續序列化整個世界；
尋找控制身份可掃原列表，O(n)時間/O(1)額外空間。保留元素仍按原輸入序送出。
超額者整筆省略，後續候選仍可使用剩餘空間；不在多位玩家廣播時重算選取/編碼。
收端相同frame與合計counts上限，元素配置前扣額度。單次scratch capacity不超剩餘payload；
所保存payload總量不超260096B，兩者合計不超520192B（不含來源模型/物件header/最終網路buffer）。

small須全部保留；oversize只省略id999並明示控制未顯示、其餘small完整；
shell-heavy須保留控制511和最前63片，恰16384格；dense須保留控制梁63，
其餘按同一凍結byte/格/站點預算與原序貪心選取，至少有省略，不改任何保留樣本。
邊界测试以獨立小語料的實際codec bytes釘住limit/limit-1，生產budget不得隨測試變動。

## 2026-09-08 收版計數

core316（304PASS/12SKIP）、Forge80PASS，共396登錄/384PASS/12SKIP。固定probe不列JUnit項數。
十變異各1test/1 AssertionFailedError/0error/0skip：TX_BYTES、TX_BLOCKS、TX_STATIONS、PRIORITY、
OMISSION、RX_FRAME、RX_BLOCKS、RX_STATIONS、RX_ID與ORDER；原序守門另有明示咬合。
最終four-fixture bytes=6039/259973/6040/193342，內容與界線見evidence；硬預算未移。
small累計配置增加到約71.8KB（原55.9KB），時間僅RECORDED_ONLY；未跑i9/Linux或真Minecraft。
