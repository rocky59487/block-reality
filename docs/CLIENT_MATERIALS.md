# CLIENT_MATERIALS：真客戶端、宣告方向與讀數

2026-09-10，模組限定，從 #110 `139a271` 接續。先凍再實作/啟動。

目的不是以 dev client 冒充 N25：沿用原 N25-e 的已安裝 Minecraft 1.20.1 / Forge 47.4.10
與真 jar；保留既有 mods 檔的備份及新 jar hash，只進入本工作建立的測試世界/伺服器。
Windows 尚未有本輪合格 native library，允許同機 Linux 真原生 dedicated server 提供分析，
但這樣不能通過 N25-g（客戶端原生自解），不得稱完整 N25 或離線 release jar 已合格。

## 固定場景與判準

| gate | 必須看到的證據 |
|---|---|
| CM-1 | 真 jar 進 1.20.1/47.4.10 客戶端、加入測試世界且零 mod crash；記錄 profile/實際 log/版本/hash。dev client 或不同 profile 只能算補充 |
| CM-2 | 測試 server 先有支承梁/柱/板原生結果；玩家加入後沒有再編輯/resolve 也收到 HUD；D/C、挫屈狀態與同 revision 指令一致 |
| CM-3 | 真 socket 的跨維度、返回、重生、登出重連：舊畫面清掉，新來源快照補送。無結構時 EMPTY；模型拒絕不能留下無說明彩色面 |
| CM-4 | 九種結構 block 的 item/block model 均載入；axis X/Y/Z/undeclared 各有可區分的呈現，兩種非方形鋼斷面不能只靠顏色區分 |
| CM-5 | 真放置的 clicked face 宣告 axis，空手蹲下互動改軸並使 revision 變動；選取輪廓、視覺軸與 server blockstate 一致，不在 Java 推導物理 |
| CM-6 | 英文/繁中至少 1280×720 與 1920×1080 的畫面可讀；長拒絕訊息、stale/local-critical/world-incomplete 與普通讀數不互相覆蓋；GUI scale 的实际值照登 |

使用 computer-use 擷取實際視窗，再改資源/布局；原畫面與首敗照留。Minecraft 自帶 F2
可保存本工作測試畫面作證據。只用此工作的虛構場景，避免把使用者桌面/帳戶畫面收入 repo。
材質可重用 Minecraft 內建的 block atlas 素材與 model transform；它們只負責外觀。
方向來源是玩家宣告 axis，不是依外觀猜 solver 軸。沒有實際圖像/互動者標待驗，不能用
JSON 存在或 unit test 通過冒充 CM-4..6。動態損傷材質與引擎剛體通道仍屬後續交付。

若啟動器需要認證，不操作帳戶對話；继续可獨立進行的模組工作，保留真 jar 驗收缺口。
原 N25-g、#86/#17、Java legacy physics 退場與最新 native release 資產資格均不因本單元縮小。

## CM-7：宣告資訊來源（可獨立於安全性視窗驗收）

真客戶端目前已載入 jar、到多人列表，Windows 防火牆權限視窗需由使用者處理。
在取得遊戲內基準畫面前，先處理材料資訊資料路徑；模型、HUD 布局仍照 CM-4..6 待驗。

- 物品資訊顯示實際 binding 對應角色與尺寸：矩形 200×400、150×300、100×200、
  木材 140×240 mm；混凝土/磚為 1 m³ 實心材料；板厚 200、150、20 mm。
- 尺寸只讀同一份送往引擎的 `game-vocabulary.json` SI 宣告，不能從 legacy token
  拆數字、不能使用舊 400×600 / 230×350 作 monolith 尺寸。單位換算僅供顯示。
- 角色分開提示：frame 放置軸是構件方向；panel 不把放置軸叫作板法向；
  monolith 不暗示沿軸縮成梁。這不改 blockstate / 遊戲採集 / solver 輸入。
- 宣告缺失、未知角色/形狀、重複名稱或非正/非有限尺寸須明示資訊不可用，不能
  把虛構預設尺寸拿去展示。顯示失敗不影響物品放置或原生 binding。
- 可重現資料測試要改相同 token 的宣告數值、交換非方形尺寸、刪除宣告及注入
  錯誤角色/尺寸，證明讀數來源與拒絕路徑。資料測試不算真畫面通過。

## Supplemental preparation amendment

The installed Windows baseline remains blocked by its security dialog. As recorded
in GATES.md and CLIENT_RENDER_PROBE.md, an isolated real Linux development-client
baseline may precede source-level material/HUD candidate edits. This changes the
preparation order only; those candidates are supplemental, and CM-1..6/N25 retain
their original installed-client/profile requirements. The Windows jar and existing
CM server stay intact for their own before/after comparison.
