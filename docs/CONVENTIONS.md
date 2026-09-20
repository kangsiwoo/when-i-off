# 개발 컨벤션

이 레포의 모든 변경은 **이슈 → 브랜치 → PR(이슈 번호 명시) → 리뷰 → 머지** 흐름을 따른다.
이슈 없는 PR, PR 없는 `main` 직접 push는 하지 않는다.

## 1. 작업 흐름

```
1. 이슈 생성 (또는 기존 이슈 선택)            #12
2. main에서 브랜치 생성                        feat/12-trip-api
3. 작업 + 커밋                                 feat(backend): trip 생성 API 추가 (#12)
4. PR 생성, 본문에 "Closes #12"                base: main
5. 셀프 리뷰 체크리스트 → 리뷰 요청
6. CI green + 리뷰 승인 → Squash merge
7. 브랜치 삭제 (이슈는 Closes로 자동 종료)
```

### 1.1 이슈
- 모든 작업은 이슈로 시작한다. 크기는 **PR 하나로 끝낼 수 있는 단위**를 목표로 하되,
  Phase 이슈(#2~#8)처럼 큰 것은 상위 이슈로 두고 하위 작업을 별도 이슈로 쪼개
  `parent`/task list로 연결한다.
- 제목 형식: `[영역] 무엇을 한다` — 예 `[backend] trip 생성 시 예측 스냅샷 자동 채움`,
  Phase 상위 이슈는 `[Phase N] ...`
- 본문은 이슈 템플릿(`.github/ISSUE_TEMPLATE/`)의 항목을 채운다: 목표, 작업 항목(체크리스트),
  완료 기준, 선행 이슈, 관련 문서. **완료 기준이 없는 이슈는 시작하지 않는다.**
- 버그는 재현 절차 / 기대 / 실제를 반드시 쓴다.

### 1.2 라벨
| 종류 | 라벨 | 용도 |
|---|---|---|
| 단계 | `phase-0` … `phase-6` | DEVELOPMENT_PLAN.md의 Phase |
| 영역 | `backend`, `analytics`, `desktop`, `ios`, `infra`, `docs` | 변경되는 디렉터리 |
| 성격 | `enhancement`, `bug`, `documentation`, `refactor`, `chore` | 변경의 종류 |
| 상태 | `blocked` | 선행 이슈/외부 승인 대기 (본문에 이유 기재) |

이슈에는 단계 + 영역 + 성격을 하나씩 이상 붙인다. PR 라벨은 이슈와 동일하게 맞춘다.

### 1.3 브랜치
```
<type>/<issue-number>-<short-kebab-description>
```
- `type`: `feat` | `fix` | `refactor` | `docs` | `chore` | `test`
- 예: `feat/3-route-crud`, `fix/21-geofence-radius`, `docs/1-conventions`
- `main`은 보호 브랜치: 직접 push 금지, PR 필수, CI 통과 필수
- 브랜치는 이슈 하나에 대응한다. 한 브랜치에 여러 이슈를 섞지 않는다
- 오래 열려 있는 브랜치는 `main`을 **merge**로 따라간다 (공유된 브랜치는 rebase 금지)

### 1.4 커밋
[Conventional Commits](https://www.conventionalcommits.org/) 형식.

```
<type>(<scope>): <subject> (#<issue>)

<body: 왜 바꿨는지. 무엇을 바꿨는지는 diff가 말해준다>
```

- `type`: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci`
- `scope`: `backend`, `analytics`, `desktop`, `ios`, `infra`, `docs`, `db`
- `subject`: 한국어 또는 영어, 명령형, 72자 이내, 마침표 없음
- 예:
  - `feat(backend): trip 생성 시 최신 예측 스냅샷 자동 채움 (#17)`
  - `fix(ios): 지하철역 geofence 반경 120m로 확대 (#21)`
  - `docs: 개발 컨벤션 추가 (#1)`
- DB 스키마 변경은 `feat(db)` / `fix(db)`로 구분해 마이그레이션 변경을 로그에서 바로 찾을 수 있게 한다
- Squash merge를 쓰므로 브랜치 안의 중간 커밋은 자유롭게 하되, **PR 제목이 최종 커밋 메시지**가
  된다는 것을 전제로 PR 제목을 커밋 규칙에 맞춘다

### 1.5 PR
- 제목: 커밋 subject 형식과 동일 (`feat(backend): ... (#17)`)
- 본문: `.github/PULL_REQUEST_TEMPLATE.md`의 항목을 채운다. 핵심은
  - `Closes #<issue>` — 반드시 포함 (여러 개면 줄마다)
  - 변경 요약과 **왜** 이렇게 했는지
  - 테스트 방법 (재현 가능한 명령 또는 절차)
  - 스키마 변경이 있으면 마이그레이션 파일명과 롤백 방법
  - 설계 문서(`docs/`)와 어긋나는 결정이 있으면 문서를 같은 PR에서 갱신
- 크기: 리뷰 가능한 범위(변경 300~400줄 이내 권장). 넘으면 이슈를 쪼갠다
- Draft PR로 열어서 CI를 먼저 돌리고, 준비되면 Ready for review
- 리뷰 코멘트에 대응한 뒤 **작성자가 스레드를 resolve하지 않고 리뷰어가 확인 후 resolve**한다

### 1.6 리뷰
- 리뷰어는 최소 1명 (1인 개발 기간에는 셀프 리뷰 체크리스트를 PR 본문에서 전부 체크하는 것으로
  대체하고, PR 열고 최소 몇 시간 뒤 다시 읽어본 다음 머지한다)
- 리뷰 관점 우선순위: 동작 정확성 → 설계 문서와의 일관성 → 테스트 → 가독성 → 스타일
  (스타일은 린터가 잡는다, 사람이 지적하지 않는다)
- 코멘트 접두어로 강도를 표시한다
  - `must:` 머지 전 반드시 수정
  - `should:` 수정 권장, 안 할 거면 이유 답변
  - `nit:` 취향, 무시 가능
  - `q:` 질문
- 승인 조건: CI green, `must` 전부 해결, 스키마 변경 시 마이그레이션 로컬 적용 확인

### 1.7 머지
- **Squash and merge**만 사용. 머지 커밋 메시지 = PR 제목 + 본문 요약
- 머지 후 브랜치 삭제 (GitHub 설정으로 자동)
- 이슈는 `Closes #`로 자동 종료. 완료 기준을 충족하지 못한 채 PR만 머지된 경우 이슈를 다시 열고
  남은 항목을 적는다

## 2. 코드 규칙 (영역별)

### 공통
- 시간은 저장·전송 모두 UTC(`TIMESTAMPTZ`, ISO-8601). 표시할 때만 KST. 시간표(`TIME`)만 KST
- 식별자는 영어 snake_case(DB) / camelCase(코드). 문서·커밋·코멘트는 한국어 가능
- 비밀값(API 키, 토큰)은 커밋하지 않는다. `.env.example`, `xcconfig` 예시 파일만 커밋
- 설계와 다른 결정을 하면 코드가 아니라 `docs/`를 고친다. 문서가 진실이다

### backend (Kotlin / Spring)
- 패키지: `com.kangsiwoo.whenioff.<domain>.{api,application,domain,infra}`
- 엔티티를 API 응답으로 직접 내보내지 않는다 (DTO 분리)
- 마이그레이션은 Flyway `V<n>__<snake_description>.sql`, 한 번 머지된 마이그레이션은 수정하지 않는다
- 린트: ktlint. `./gradlew check`가 CI 게이트
- 테스트: 단위(`*Test`) + Testcontainers 통합(`*IT`). 외부 API는 fixture로

### analytics (Python)
- Python 3.12, `uv` 또는 `poetry`로 의존성 고정, `ruff` + `mypy --strict`
- 모든 배치는 CLI 서브커맨드(typer)이며 **idempotent**해야 한다 (같은 날짜에 두 번 돌려도 결과 동일)
- 통계 로직은 순수 함수로 두고 DB I/O와 분리해서 합성 데이터로 테스트한다
- `model_version`은 알고리즘 변경마다 올린다 (`departure_recommendations`에 기록됨)

### desktop (TypeScript / React)
- Vite + React + TS strict, ESLint + Prettier
- 서버 상태는 TanStack Query, 폼은 react-hook-form. 전역 상태 라이브러리는 필요해질 때
- API 타입은 backend OpenAPI에서 생성 (`openapi-typescript`), 손으로 쓰지 않는다

### ios (Swift)
- SwiftUI, 최소 iOS 17, SwiftLint
- CoreLocation 등 시스템 프레임워크는 프로토콜로 감싸서 테스트 가능하게
- 백엔드 주소/토큰은 xcconfig 주입

## 3. 문서 규칙
- `docs/`는 살아 있는 문서다. 코드 변경으로 설계가 바뀌면 같은 PR에서 갱신
- `docs/db/schema.sql`은 **설계 참고용 스냅샷**이다. Phase 0 이후 실제 진실은 Flyway
  마이그레이션이며, 스키마가 바뀌면 schema.sql도 최신 상태로 다시 덤프해 둔다
- ADR(Architecture Decision Record)이 필요한 결정(예: PostGIS 도입, Redis 도입, 인증 방식 변경)은
  `docs/adr/NNNN-title.md`로 남긴다. 형식: 배경 / 결정 / 대안 / 결과

## 4. 이 문서의 변경
컨벤션 변경도 이슈 → PR을 거친다. 라벨 `docs`.
