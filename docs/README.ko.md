[English](README.md)

# 문서

사용자 문서, 보안 정책, 기여 안내, 기여자 참고 문서와 이 문서는 영문판(`X.md`)과 한글판(`X.ko.md`)이 함께 있습니다. 저장소 입구는 영문 [README.md](../README.md)이고 한글판은 [README.ko.md](../README.ko.md)입니다.

## 사용자 문서

Sennard를 설치해 쓰는 사람을 위한 문서입니다. 설치는 [README](../README.ko.md#설치)에 있습니다.

| 문서 | 내용 |
| --- | --- |
| [사용하기](user/usage.ko.md) | 요청하는 법, 설치하면 달라지는 동작, workflow와 편집 관문, 작업 기록, 하위 작업의 모델 선택, 보장하지 않는 것 |
| [전체 흐름도](user/system-flow.ko.md) | 사용자 요청 처리, 세션 수명, workflow 상태 전이, 세션 간 협업 |
| [관리자 페이지와 컨텍스트](user/admin-page.ko.md) | 로컬 관리자 페이지, 전역·담당·상황 컨텍스트, JEV, 언어, 재설정 |
| [세션 협업](user/collaboration.ko.md) | 현황판, PEER 메시지, 호스트별 깨우기 |
| [상태 저장과 정리](user/state.ko.md) | 저장소 위치와 설정, 정리·백업·복구 |
| [업데이트와 이전](user/update.ko.md) | 새 판 알림, 업데이트 절차, 플러그인 훅 |
| [문제 해결](user/troubleshooting.ko.md) | 증상별 확인할 것 |

## 기여자 문서

Sennard 자체를 고치는 사람을 위한 문서입니다. 시작은 [기여 안내](../CONTRIBUTING.ko.md)입니다. 아래 문서는 각각 영문판과 한글판이 있습니다.
| 문서 | 내용 |
| --- | --- |
| [아키텍처](contributing/architecture.ko.md) | 스킬·계약·MCP 서버 구조, 실행 관측, 작업 기록, 현황판, 메시지 저장소, 검사 범위, 저장소 구조 |
| [개발 참고](contributing/development.ko.md) | 개발 환경, 검증 명령, 생성물, 동결 평가, 스킬 추가·편입 |
| [시험과 CI](contributing/testing-and-ci.ko.md) | CI와 시험 서버의 역할, 시험 범위 나누기 |
| [발행](contributing/release.ko.md) | 판 번호, 릴리스 노트, 발행 전 점검, 공급망 |
| [문서 포맷](contributing/document-format.ko.md) | 문서 언어, 릴리스 노트 포맷, 메인 README 포맷 |
| [호스트 훅 수신](contributing/hooks.ko.md) | 호스트·이벤트별로 MCP 도구 훅과 command 훅으로 실행하는 Sennard Java 클라이언트 중 무엇을 쓰는지와 그 공식 문서 근거 |
| [국제화](contributing/i18n.ko.md) | 모델용 locale 고정, 메시지 카탈로그, 단일 패키지 |
| [실행 관측과 호스트 연결](contributing/execution-attestation.ko.md) | 호스트별 실행 관측 출처와 실패 코드 |
| [세션 메시지 계약](contributing/session-messaging.ko.md) | 메시지 ID·재시도·회수·ACK, 깨우기, presence, peer 대기 판정 |
| [입력 출처와 작업 경계](contributing/input-boundaries.ko.md) | 입력 출처 구분, 수신·재전달, workflow 원장의 쓰기 대상 |
| [관리자 서명 기록과 설정 읽기](contributing/admin-log.ko.md) | 서명 기록 형식·검증, 설정 읽기, JEV 구성, 오류 코드 |
| [DB와 트랜잭션](contributing/db-and-transactions.ko.md) | Spring Data JDBC 리포지토리, 계정 격리, 동시성, 값은 Java가 만들고 DB는 공통 타입, ANSI SQL |
| [첨부와 업로드](contributing/attachments.ko.md) | 첨부 표, 암호화 세그먼트 형식, `artifact_*` 도구, 설정, 오류 코드, 키, 정리, 백업, `sennard attachments` 명령, 단계 결과와의 연결 |
| [예외와 메시지](contributing/exceptions-and-messages.ko.md) | `SennardException` 일곱 범주, 코드 enum, 메시지 카탈로그(영·한), MCP·HTTP 결과 변환 |
| [검증](contributing/validation.ko.md) | Jakarta Validation 선언, 입력 record·서비스 메서드·설정 검증, 도메인 규칙의 자리 |
| [도구 입구 매핑](contributing/tool-input-mapping.ko.md) | MCP 도구 입력을 평평한 키로 받아 입력 record로 바꾸는 방식과 그 실측 근거 |
| [설정과 프로파일](contributing/settings-and-profiles.ko.md) | `@ConfigurationProperties` record, 주입하는 빈, 배치 차이와 프로파일 |
| [로깅](contributing/logging.ko.md) | SLF4J·Logback, 예외 남기는 모양, 금지 목록 |
| [로드맵](contributing/roadmap.ko.md) | 개인판 1.0.0 이후의 방향 |

## 릴리스 노트

- [릴리스 노트](release-notes/): 판마다 `release-notes-v<버전>.md` 하나입니다. 개인판은 [1.0.0](release-notes/release-notes-v1.0.0.md)부터 시작하며, 개인판 노트는 한 파일에 영문을 먼저, 한글을 이어서 적습니다. GitHub Release 본문도 이 파일로 만듭니다.
