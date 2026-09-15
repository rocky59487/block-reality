# Alpha 安裝與開發

1. 安裝 Minecraft Java 1.20.1、Forge 47.x、Java 17。
2. 將發布的 `blockreality-0.4.0-alpha.1.jar` 放進客戶端與伺服器的 `mods/`。
3. 啟動遊戲。Windows／Linux x86_64 引擎已在 JAR 裡；Linux 需要 glibc 2.35 以上。
   系統不支援或原生引擎被拒絕時，分析會明確顯示不可用。

結構材料與放置方向由模組保存；應力眼鏡／分析指令顯示引擎回傳的結構結果。
原生引擎能力與 Minecraft 操作流程的完成度分開記錄，見 [alpha 邊界](docs/ALPHA_RELEASE.md)。

## 從原始碼封裝

準備 Python 3、JDK 17、GitHub CLI，並登入能讀取 tectonic2 的帳號。Gradle wrapper 隨倉提供。

```sh
python scripts/package_native.py --out build/release-alpha
# 已下載包含兩 ZIP、source.tar.gz、SHA256SUMS 的完整 SDK：
python scripts/package_native.py --release-dir /path/to/sdk-assets --out build/release-offline
```

Windows 可使用 `python`；Linux 也可用 `python3` 或 `bash scripts/package.sh`。
輸出目錄必須不存在。失敗保留原有 dist，成功後新目錄包含 JAR、雜湊、授權與引擎來源紀錄。
`native-release.json` 是固定版本來源，封裝不追蹤 latest 或任意分支。

日常核心測試：`cd mod && ./gradlew test`；若要真引擎測試，設定 `BR_ENGINE` 為原生庫絕對路徑。
完整封裝命令會自行設定該路徑、執行核心與 Forge 測試。

比對既有 native fixture 的逐位元結果時，使用 `OPENBLAS_CORETYPE=Haswell`、
`OPENBLAS_NUM_THREADS=4`；CI 與完整封裝入口已固定這組驗收環境。
