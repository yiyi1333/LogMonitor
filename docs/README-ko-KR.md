# LogMonitor

현재 버전: **v1.1.0**

LogMonitor는 Spring Boot 로그를 수집하고 분석하여 애플리케이션 네임스페이스별 트래픽 추세, 오류 그룹, 비식별화된 상세 정보와 LLM 분석을 제공하는 운영 플랫폼입니다.

## 빠른 링크

- 중국어/영어 전체 README: [루트 README](../README.md)
- 서비스 아키텍처: [ARCHITECTURE.md](ARCHITECTURE.md)
- 런타임 아키텍처: [RUNTIME_ARCHITECTURE.md](RUNTIME_ARCHITECTURE.md)
- 배포 및 설치: [deploy/README.md](../deploy/README.md)

## 주요 기능

- Vue 3 + TypeScript 프런트엔드, Spring Boot 3 / JDK 17 중앙 서버, JDK 8 Agent.
- 운영 환경 MySQL 8.0.36+, 로컬 데모는 메모리 H2 사용.
- 로컬/원격 수집, 로그 순환 감지, 중복 제거, 최대 5 GB 디스크 큐.
- 네임스페이스, URI, 인스턴스, 분 단위 집계와 오류 상세 조회.
- LLM 전송 전 token, Cookie, 비밀번호, 전화번호, 신분증, IP 마스킹.

## 로컬 실행

[Quick Start](../README.md#quick-start)에 따라 JDK 17과 Node.js 20.19+/22.12+를 준비하세요. Agent 등록은 [Agent 설치 안내](../deploy/README.md#설치-agent)를 참조하세요.

## 버전 정책

개발 커밋은 patch를 `+0.0.1`, 정식 릴리스는 minor를 `+0.1.0` 올리고 patch를 0으로 되돌립니다. 세 구성 요소의 버전은 항상 동일해야 합니다.
