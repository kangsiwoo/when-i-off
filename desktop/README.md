# desktop

TypeScript + React(Vite) 관리·조회 웹. 지금은 토큰 로그인 → 경로 목록 → 경로 상세(구간 목록)(#56)와
이동 기록(trip 히스토리: 목록·타임라인·시각/결과 보정, #58), 경로별 캘리브레이션 상태(#60), 추천 vs 실제(#62)가
있다. 지도 위 경로/구간/정류장/신호등 편집은 이 골격 위에 올린다.
상세는 [docs/DEVELOPMENT_PLAN.md](../docs/DEVELOPMENT_PLAN.md) Phase 5, 이슈 #7.

스택: Vite · React 18 · TypeScript(strict) · TanStack Query · react-router · openapi-fetch · Recharts(차트),
검사는 ESLint(flat config) · Prettier · Vitest + Testing Library(jsdom). Node 22, npm(`package-lock.json` 커밋).

## 실행

```bash
cd desktop
cp .env.example .env      # VITE_API_BASE=http://localhost:8080
npm ci
npm run dev               # http://localhost:5173
```

backend를 먼저 띄운다([backend/README.md](../backend/README.md)). 로그인 화면에 backend의 `WIO_API_TOKEN` 값을 넣는다.
토큰은 브라우저 localStorage(`wio.apiToken`)에 저장되고 모든 요청에 `X-Api-Token` 헤더로 붙는다.
API가 `401`을 주면 토큰을 지우고 로그인 화면으로 돌아간다.

### 백엔드 주소와 CORS

브라우저는 항상 같은 출처의 `/api/v1/...`로 요청한다. `npm run dev`와 `npm run preview`는 `/api`를
`VITE_API_BASE`(기본 `http://localhost:8080`)로 프록시하므로 backend에 CORS 설정이 필요 없다.
빌드 결과(`dist/`)를 배포할 때도 같은 출처에서 `/api`를 backend로 넘기는 리버스 프록시 뒤에 둔다.

## 검사

```bash
npm run typecheck
npm run lint
npm run format:check      # 고치려면 npm run format
npm test
npm run build
```

CI(`.github/workflows/desktop-ci.yml`)가 같은 순서로 돌리고, 마지막에 생성 타입이 최신인지 본다(아래).

## API 타입 재생성

API 타입은 손으로 쓰지 않는다. backend springdoc 스펙을 `openapi.json`으로 커밋하고, 그것으로
`src/api/schema.d.ts`를 생성해 같이 커밋한다. 화면은 `src/api/client.ts`의 별칭(`CommuteRoute` 등)을 거쳐
생성된 `components["schemas"]`만 쓴다.

backend API를 바꿨으면:

```bash
# 1) backend 스펙을 desktop/openapi.json으로 내보낸다.
#    test 프로필로 앱을 띄워 /v3/api-docs를 받는다. 테스트와 같은 로컬 PostgreSQL(테스트 DB)이 필요하다.
cd backend
WIO_DB_URL=jdbc:postgresql://localhost:5432/when_i_off_test WIO_DB_USER=wio WIO_DB_PASSWORD=wio \
  ./gradlew exportOpenApi

# 2) 타입을 다시 만든다 (openapi-typescript → prettier).
cd ../desktop
npm run gen:api

# 3) openapi.json과 src/api/schema.d.ts를 API 변경과 같은 PR에 커밋한다.
```

`exportOpenApi`는 키를 정렬하고 들여쓰기를 고정해 쓰므로 스펙이 그대로면 파일도 바뀌지 않는다.
낡은 파일은 CI가 잡는다.

- **backend-ci** `OpenAPI spec is up to date`: `exportOpenApi`를 돌려 `desktop/openapi.json`에 diff가 있으면 실패
- **desktop-ci** `Generated API types are up to date`: `gen:api`를 돌려 `src/api/schema.d.ts`에 diff가 있으면 실패

## 이동 기록 (#58)

- `/trips?routeId=&from=&to=`: `GET /commute-trips` 필터를 주소에 둔다. 행마다 날짜, 집 나섬 → 도착, 총 소요,
  결과(TRANSIT 구간 중 탄 구간 수, 놓친 차 수)
- `/trips/:id?routeId=&date=`: trip 단건 조회 API가 없어 목록 조회를 그 경로·날짜로 좁혀 꺼낸다
  (`tripDate`는 PATCH로 바뀌지 않는다). 타임라인은 시각이 아니라 구간 `seqOrder` → `attemptSeq` 순서다.
  시도마다 예측 스냅샷과 `실제 − 예측`(초)을 보여 준다
- 보정은 바뀐 필드만 PATCH로 보낸다. 서버는 `null`을 "그대로"로 보고 시각을 지우는 API가 없으므로 기록된 시각
  칸을 비우면 막는다. 400(#37/#38 시각 순서 규칙)은 `detail`을 그대로 보여 주고, 저장하면 `["commute-trips"]`
  캐시를 무효화해 목록·상세를 다시 받는다
- 시각 표시·입력은 모두 KST다. `datetime-local` 값은 브라우저 시간대가 아니라 KST 벽시계로 해석한다(`src/kst.ts`)

## 캘리브레이션 상태 (#60)

- `/routes/:id/calibration`: 경로 상세의 "보정 상태" 탭. `GET /commute-routes/{id}/calibration` 하나로 그린다
- 도보 구간: 구간 행의 평균 속도 ± σ, 샘플 수, 갱신 시각. 샘플이 `minSamples`(5) 미만이면 "신뢰도 낮음" 배지.
  그 아래 **추천에 실제로 쓰이는 값**과 출처(이 구간 기록 → 전체 도보 기록 → 기본값)
- 대중교통 구간: 예측 오차(bias ± σ)와 차내 시간(평균 ± σ)의 day_type × 30분 밴드 표. 샘플 부족 행은 배경색과
  배지로 표시하고, 그런 시간대에 내려가는 값(모든 시간대를 합친 값 → 기본값)을 표 아래 적는다
- 행이 없으면 "아직 기록이 없어 기본값으로 추천합니다"
- 고르는 순서와 기본값은 analytics(`model/lookup.py`, `defaults.py`)를 옮긴 `src/calibration/chain.ts`에 있다.
  저쪽을 바꾸면 여기도 같이 바꾼다

## 추천 vs 실제 (#62)

- `/routes/:id/recommendations?from=&to=`: 경로 상세의 "추천 vs 실제" 탭. `GET /commute-routes/{id}/recommendation-history`
  하나로 그린다. 기간은 KST 날짜이고 기본은 오늘(KST)까지 최근 30일, 프리셋 7/30/90일
- 차트 두 개(Recharts, 이 탭에서만 쓰므로 lazy로 따로 읽는다): ① 날짜별 추천 출발(모델 버전별 선)과 실제 출발(점),
  y는 KST 하루 중 시각 ② 날짜별 `bufferSeconds`(분, 0부터). 단위가 달라 축 두 개짜리 한 차트로 겹치지 않는다.
  범례 + 겹치지 않는 끝점에만 직접 라벨 + 크로스헤어 툴팁. 표가 차트의 표 보기를 겸한다
- 색은 dataviz 기준 팔레트(`index.css`의 `.viz` 토큰)이고 `validate_palette.js --pairs all`로 앱 표면(라이트 `#ffffff`,
  다크 `#1a1b1e`)에 대해 검사했다. 실제 출발 = 1번, 버전은 `vN`의 N이 홀수면 2번·짝수면 3번(버전이 늘 같은 색).
  같은 자리를 원하는 더 오래된 버전과 번호 없는 버전은 회색으로 접는다
- 표: 날짜(그날 trip이 여럿이면 trip마다 한 줄) / 추천 출발(버전별, 확률·여유) / 실제 출발과 버전별 차이(±분, +면
  늦게 나섬) / 목표 도착 / 실제 도착과 지각 여부 / 결과(전 구간 탑승, 놓친 차). 지각 판정 기준은 그날 가장 늦게
  계산된 추천의 목표 시각이고, 1초라도 늦으면 지각이다
- 계산은 `src/recommendations/history.ts`(화면 없는 순수 로직)

## 구조

```
src/
  api/        schema.d.ts(생성), client.ts(openapi-fetch + 토큰/401 미들웨어), queries.ts(TanStack Query 훅)
  auth/       token.ts(localStorage), RequireAuth.tsx
  pages/      LoginPage, Layout, RouteListPage, RouteDetailPage,
              TripListPage, TripDetailPage(+ TripTimeline, TripTimesForm, AttemptForm),
              RouteCalibrationPage(+ RouteTabs), RouteRecommendationsPage(+ RecommendationCharts, lazy)
  calibration/ chain.ts(보정값 조회 순서·기본값 — analytics lookup.py를 옮김) — 화면 없는 순수 로직
  recommendations/ history.ts(추천 vs 실제: 차이·지각·차트 계열·축·버전 색) — 화면 없는 순수 로직
  trips/      timeline.ts(타임라인·결과 요약·예측 오차), corrections.ts(보정 폼 → PATCH 본문) — 화면 없는 순수 로직
  kst.ts      datetime-local ↔ UTC ISO. 브라우저 시간대와 상관없이 KST(UTC+9)로 읽고 쓴다
  routes.tsx  라우트 표
  context.ts  라우터 · QueryClient · API 클라이언트 묶음 (테스트는 메모리 라우터와 가짜 fetch를 넣는다)
```
