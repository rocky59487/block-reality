# 遊戲內實跑手冊（N25）

判準在 `docs/GATES.md` 的 2026-09-05 節。本檔是**怎麼跑**，不是**什麼算過**——
兩者分開，是因為手冊會隨環境改，判準不會（改判準要進異動登記表）。

這份手冊的存在理由很難堪：到 2026-09-05 為止，**這個 mod 從來沒有在跑起來的遊戲裡開過**。
`forge/run/` 不存在就是證據。N1–N24 檢查的全是位元組與邏輯。

---

## 0. 前置

| 項 | 值 | 怎麼確認 |
|---|---|---|
| 客戶端 | Minecraft `1.20.1` + Forge `47.4.10` | `%APPDATA%/.minecraft/versions/1.20.1-forge-47.4.10` |
| 開發側 Forge | `47.4.13`（`forge/build.gradle`） | 版號不同是正常的；同一個 MC 版本內相容 |
| 引擎 | `dist/br-sidecar.exe`（Windows）、`dist/br-sidecar`（Linux） | `sidecar/main.cpp` 的最後改動必須**早於**這兩顆的 mtime，否則先重建（`scripts/package.sh`） |

**jar 形狀**：預設 `-PbrEngineDir=../dist`、`-PbrNativesDir=none` ⇒ **sidecar shape**，
Windows 可用。natives shape（`-PbrNativesDir`）今天只有 Linux 函式庫（#90），**不要**拿它跑這份手冊。

---

## 1. 伺服器腿（N25-a..d）——不需要 GUI

```bash
./gradlew :forge:runServer
```

首次會因 EULA 停下。同意後（`forge/run/eula.txt` 改 `eula=true`）再跑一次。

伺服器讀 stdin，所以指令可以直接餵。要看的東西：

1. **N25-a** —— log 走到 `Done (`；`com.blockreality` 沒有 ERROR、沒有 exception
2. **N25-b** —— 引擎那一行說得出「找到並握手」或「沒有並具名理由」
3. **N25-c** —— 空世界下 `/br status`、`/br members`、`/br section` 各回一個說得通的答案
4. **N25-d** —— 用 `/setblock` 或 `/fill` 蓋一根落地柱 + 一根樑，`/br status` 要回非零 member 數與一個 max D/C

`BR_SIDECAR` 環境變數會被 `forge/build.gradle` 轉成 `-Dbr.sidecar`。**dev 執行的 jar 沒有內建引擎**
（`runServer` 走 `sourceSets.main`，不經 `bundleEngines`），所以這一腿必須靠它指路：

```bash
BR_SIDECAR=$PWD/dist/br-sidecar.exe ./gradlew :forge:runServer
```

沒設就是「沒有引擎」——那是 N25-b 的合法結局，但 N25-d 就跑不到。

## 2. 客戶端腿（N25-e..g）——要眼睛

```bash
./gradlew :forge:build
```

產出 `forge/build/libs/blockreality-0.4.0-dev.jar`（**帶引擎**，因為走了 `bundleEngines`）。

1. 複製到 `%APPDATA%/.minecraft/mods/`
2. Minecraft Launcher 選 `1.20.1-forge-47.4.10` 啟動
3. 進創造模式世界，蓋同一組柱 + 樑
4. **N25-e** 零 crash；**N25-f** HUD 的 D/C 與 `/br status` 對**同一根構件**是同一個答案；
   **N25-g** 遊戲目錄下解出來的引擎 sha256 與 jar 內 `blockreality-engine/manifest` 相符

N25-f 是這一腿的重點。它咬的是本倉庫的常見缺陷型態：**同一個數字兩個介面兩個答案**
（PR #82：覆蓋層把 D/C 扣住，聊天視窗照印）。

## 2b. 客戶端腿的最短路徑（給有螢幕的人）

jar 已就位時，這一段是四分鐘的事：

1. 啟動器選 `1.20.1-forge-47.4.10` → 建創造模式**超平坦**世界
2. `/give @s blockreality:steel_beam 64` 與 `/give @s blockreality:stress_glasses`
3. 蓋一座門形框：兩根 5 格柱，間隔 6 格，頂上用樑接起來
4. `/br scan`（**必要**——指令與 worldgen 放的方塊不進結構集合，只有手放的會）
5. `/br status` 記下 max D/C 與是第幾號構件
6. 戴上應力眼鏡看**同一根**構件的 HUD 讀數

**N25-f 的通過條件就是第 5 步與第 6 步給同一個答案。**
不同就是紅，照登，不要重蓋一座。

## 3. 照登

結果——**包含紅的**——寫進 `docs/GATES.md` 的 dated 節。
不換 fixture、不事後重詮釋（鐵則 3）。第一次跑預期會紅；紅不是失敗，藏起來才是。
