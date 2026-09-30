# LogMonitor

目前版本：**v1.0.9**

LogMonitor 是面向內部維運團隊的 Spring Boot 日誌分析平台，支援本機目錄與遠端 Agent 採集、錯誤分組、介面趨勢、脫敏及多供應商 LLM 分析。

## 快速入口

- 完整中英 README：[根目錄 README](../README.md)
- 服務架構：[docs/ARCHITECTURE.md](ARCHITECTURE.md)
- 執行架構：[docs/RUNTIME_ARCHITECTURE.md](RUNTIME_ARCHITECTURE.md)
- 發佈與安裝：[deploy/README.md](../deploy/README.md)

## 主要特性

- Vue 3 + TypeScript 前端、Spring Boot 3 / JDK 17 中心端、JDK 8 遠端 Agent。
- MySQL 8.0.36+ 生產資料庫，本機示範使用記憶體 H2。
- 依應用程式命名空間聚合訪問量和錯誤，支援跨伺服器下鑽。
- Agent 使用 HTTPS、Bearer Token、gzip 批次和最多 5 GB 磁碟佇列。
- LLM 送出前會遮罩 token、Cookie、密碼、手機、身分證和 IP。

## 本機啟動

請依照 [Quick Start](../README.md#quick-start) 設定 JDK 17、Node.js 20.19+/22.12+，再啟動 backend、frontend；Agent 設定請參考 [Agent 安裝章節](../deploy/README.md#安裝-agent)。

## 版本規則

開發提交版本遞增 `+0.0.1`，正式發佈版本遞增 `+0.1.0` 並將 patch 歸零。三個元件版本必須同步。
