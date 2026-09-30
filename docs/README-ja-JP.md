# LogMonitor

現在のバージョン：**v1.0.9**

LogMonitor は、Spring Boot ログを収集・解析し、アプリケーション名前空間ごとのアクセス傾向、エラーグループ、マスク済み詳細、LLM 分析を提供する運用向けプラットフォームです。

## クイックリンク

- 完全な中国語/英語 README：[ルート README](../README.md)
- サービスアーキテクチャ：[ARCHITECTURE.md](ARCHITECTURE.md)
- 実行アーキテクチャ：[RUNTIME_ARCHITECTURE.md](RUNTIME_ARCHITECTURE.md)
- リリースとインストール：[deploy/README.md](../deploy/README.md)

## 主な機能

- Vue 3 + TypeScript、Spring Boot 3 / JDK 17、JDK 8 Agent。
- 本番 MySQL 8.0.36+、ローカルデモはインメモリ H2。
- ローカル/リモート収集、ローテーション検出、重複排除、5 GB ディスクキュー。
- 名前空間、URI、インスタンス、分単位の集計とエラーのドリルダウン。
- LLM 送信前に token、Cookie、パスワード、電話番号、ID、IP をマスキング。

## ローカル起動

[Quick Start](../README.md#quick-start) に従い、JDK 17 と Node.js 20.19+/22.12+ を準備してください。Agent の登録は [Agent セクション](../deploy/README.md#インストール-agent) を参照してください。

## バージョン

開発コミットは patch を `+0.0.1`、正式リリースは minor を `+0.1.0` 増やします。3 コンポーネントのバージョンは常に一致させます。
