# LogMonitor

Aktuelle Version: **v1.0.1**

LogMonitor ist eine Betriebsplattform zur Analyse von Spring-Boot-Logs. Sie sammelt lokale und entfernte Dateien, gruppiert Fehler, aggregiert Trends nach Anwendungs-Namespace und kann nach der Maskierung sensibler Daten LLM-Anbieter aufrufen.

## Schnellzugriff

- Vollständige chinesisch/englische README: [Root README](../README.md)
- Service-Architektur: [ARCHITECTURE.md](ARCHITECTURE.md)
- Laufzeitarchitektur: [RUNTIME_ARCHITECTURE.md](RUNTIME_ARCHITECTURE.md)
- Veröffentlichung und Installation: [deploy/README.md](../deploy/README.md)

## Funktionen

- Vue 3 + TypeScript, Spring Boot 3 / JDK 17 und JDK-8-Agent.
- MySQL 8.0.36+ in Produktion; lokales Demo mit In-Memory-H2.
- Lokale/entfernte Sammlung, Rotationserkennung, Deduplizierung und 5-GB-Disk-Queue.
- Aggregation nach Namespace, URI, Instanz und Minute mit Fehlerdetails.
- Maskierung von Token, Cookie, Passwörtern, Telefonen, Ausweisen und IPs vor LLM-Aufrufen.

## Lokaler Start

Folge [Quick Start](../README.md#quick-start) mit JDK 17 und Node.js 20.19+/22.12+. Für die Agent-Registrierung siehe [Agent-Installation](../deploy/README.md#安装-agent).

## Versionierung

Ein Entwicklungs-Commit erhöht patch um `+0.0.1`; ein offizielles Release erhöht minor um `+0.1.0` und setzt patch auf null. Alle drei Komponenten verwenden dieselbe Version.
