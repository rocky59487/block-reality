# Block Reality 0.4.0-alpha.1

配套引擎為 Tectonic `v2.0.0-alpha.1`。這次整理主線、版控與原生封裝，供模組開發及試用。

- 同時整合原生分析／材料幾何／斷裂權威與施工交易、持久化、方向放置功能。
- 一個 JAR 內含 Windows／Linux x86_64 原生引擎、來源 provenance 與第三方授權。
- 固定 SDK source commit、契約與完整資產雜湊；CI 驗證實際隨包原生庫與 Java 接線。
- 安裝：Minecraft 1.20.1、Forge 47.x、Java 17，JAR 放入雙端 mods/。
  Linux 需要 glibc 2.35 以上；macOS／ARM 尚無隨包引擎。

此 alpha 並非完整 v2。引擎的倒塌、碎塊、剛體及非線性入口已存在；
不能據此宣稱所有 Minecraft 遊戲操作、渲染與長時間世界生命週期均已驗收。
完整 v2／Perfect Engine 的功能、原數值與效能門檻仍按引擎 V2_DELIVERY 追蹤。
本次 Java／Forge suites 與 JAR 載入驗證也不代替實際 Minecraft 遊玩測試。
