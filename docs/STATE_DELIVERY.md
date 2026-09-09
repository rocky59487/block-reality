# STATE_DELIVERY：有來源的狀態、空模型與進入世界

2026-09-10，模組限定；接 #109。判準先凍後實作。

現有 EngineStatusPacket 只有兩個字串，無 dimension/revision；ClientStressState 直接接受。
錯誤可覆蓋另一個世界的結果，舊 result 也沒有順序守門。拆完最後一塊時只有 dirty=false，
沒有清掉既有結果；登入/跨維度/重生沒有主動補送已有快照。這些不是引擎工作。

## 交付格式與權威

Forge channel 改為 11；只註冊一種有界 AnalysisUpdatePacket，承載 RESULT/PENDING/
EMPTY/OFF/MODEL_REFUSED/ENGINE_UNAVAILABLE。BSI contract/pin 不改。
共同 envelope 包含 dimension、sourceId（StructureManager 每次建立的 UUID）、全 JVM
單調 delivery sequence、world revision、bootstrap 旗標。RESULT 內保留既有完整
StressResultPacket 的 result revision、樣本、旗標與限制；其他種類不帶結果。

sourceId 是模組來源實例的短期身分，**不是持久 Artifact ID**，不冒充 #17/#86 已完成。
bootstrap 由登入、重生、跨維度事件補送當前結果/狀態；沒有新編輯也必須得到資料，
不能為加入玩家而強迫重算。result 可為舊快照，但 envelope 的 world revision 必須真實，
客戶端明示 stale。來源更换只有 bootstrap 能建立；一般資料不能自行切換來源。

客戶端先比對真正的 NetworkEvent connection 與目前 Minecraft connection，並要求
有目前 level；再檢查 dimension、source、sequence、revision。切維度清掉畫面和綁定，
保留連線內 sequence 高水位；登出/換連線才全清。不得讓晚到的 bootstrap 或舊來源包
重建已失效畫面。相同 revision 的 probe/恢復可以有新 sequence，不能只按 revision 去重。

EMPTY 明示沒有已追蹤結構，不是假造零應力的成功分析。清掉 latest、展示與 pending。
OFF 在 locate/load 之前保持生效，清展示但仍可建造。失敗與模型拒絕分開顯示，
不得讓 READY + refused 顯示成引擎損壞；新成功结果可清掉先前拒絕。
PENDING 保留可辨識的過期展示；錯誤/停用/空模型不留下無說明的彩色表面。

## 固定驗收

| gate | 要求 |
|---|---|
| SD-1 | pure AnalysisDeliveryClock：wrong dimension、未 bootstrap、old source、duplicate/old sequence、revision 倒退均拒絕；拒絕不修改目前 clock |
| SD-2 | 登出/換連線清 epoch；同連線切維度保留 sequence；返回同 dimension 需新 bootstrap，晚到舊 bootstrap 不可覆蓋 |
| SD-3 | 同 revision 的新 sequence 能讓 refusal→result；payload result revision 不超過 world revision，也不能倒退覆蓋較新結果 |
| SD-4 | envelope round-trip 每種狀態；逐 byte 截斷/壞 kind/負 seq或revision/跨 dimension result/多餘payload 都 fail closed；decoder 不炸遊戲 |
| SD-5 | 整包仍 ≤256KiB，既有總格/站點與控制元素優先不變；outer envelope 的最大開銷要實量並入 budget，不藉新版協定放大上限 |
| SD-6 | 真 server 拆光→EMPTY、命令不再顯示舊結果；再建→真 native 分析；off 起動不載庫，再開可恢复；補送函式實際由登入/重生/跨維度事件調用 |
| SD-7 | 真 native result→envelope→decoder→clock 的旗標/樣本保真；純接收狀態與封包整合可在 headless 驗，沒有真客戶端/雙玩家證據仍不能叫 N25 或 v1 |

具名故障臂先凍：忽略來源相等→rejectsAnotherSourceUntilBootstrap；忽略sequence比較→
rejectsLateAndDuplicateUpdates；忽略dimension比較→rejectsOtherDimensionsWithoutChangingState。
每臂都需編譯成功、唯一該斷言 FAIL。原測試與失敗紀錄照留。

## 後續依賴

持久融合區域、chunk 次序不變與集合成長界限依 #86，另有完整 WORLD_IDENTITY 程序；
本 envelope 只能解交付身分，不能將目前的載入集合當持久世界。損傷/材料交易的持久
Artifact/event token 依 #17 仍待接引擎，不在 Java 模擬碰撞或演算結構分解。

## 伺服器事件的 headless 證據範圍

SD-6 的事件路徑另用 integrationTest 專用 probe：在隔離真 server 建立測試 ServerPlayer，
向 Forge event bus 發送登入/重生/跨維度事件，攔截 Connection.send 所送的實際 channel
payload，decode 並交付 clock；確認 result/revision 沒有因補送而重算。
這能驗事件註冊、manager、channel、編碼與來源路徑，不能驗真登入/socket/client clone
時序。probe 不進正式 source set/jar，N25 與實際雙玩家/旅行驗收仍待執行。

SD-6 第三次 smoke 的補送 probe 已過，但 RCON 輔助工具把 command 與 delimiter 連續送出；
Minecraft 1.20.1 RconClient 一次 read 若收到兩個 frame 會直接斷線。修輔助工具前固定：
先收第一個命令回覆，再送 delimiter；保留分段回覆拼接；UTF-8 以 bytes 計長；斷線/截斷
明示失敗，不能回空字串冒充結果。用分段 socket fixture 驗命令順序與長回覆，再重跑真 server。

模組 #109 的 CI attempt 1/2 均停在 apt update 的外部 Chrome repository hash mismatch，
尚未執行 host gate。此 job 只需 cmake/g++；先檢查 runner 已有可執行工具，缺失才安裝，
列印實際版本後仍必須建置並執行全部 host/corpus gate，不容許忽略 apt 失敗或把未跑視為 PASS。
