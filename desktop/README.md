# desktop

TypeScript + React(Vite) 관리·조회 웹. 지금은 토큰 로그인 → 경로 목록 → 경로 상세(구간 목록)(#56)와
이동 기록(trip 히스토리: 목록·타임라인·시각/결과 보정, #58), 경로별 캘리브레이션 상태(#60), 추천 vs 실제(#62)가
있다. 지도 위 경로 등록·구간/정류장/신호등 편집과 trip GPS 트랙(#64), 정적 시간표 CSV 업로드와 다음 출발 확인(#70), 운영 화면(외부 API 호출·실패·지연·한도 사용률과 배치 상태, #76)도 있다.
상세는 [docs/DEVELOPMENT_PLAN.md](../docs/DEVELOPMENT_PLAN.md) Phase 5, 이슈 #7.

스택: Vite · React 18 · TypeScript(strict) · TanStack Query · react-router · openapi-fetch · Recharts(차트),
Leaflet + react-leaflet 4(지도, React 18용 마지막 메이저),
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
- **버전별 성과(#79)**: 차트 위에 `GET /commute-routes/{id}/recommendation-evaluations`(같은 기간)의 버전별 요약을 버전마다 한 줄의
  stat tile로 — 평가한 날(n) / 지각률(분모는 도착 기록이 있는 날) / 평균 출발 차이 / 평균 정류장 대기 / 전 구간 탑승률.
  숫자는 backend가 analytics `summarize()`와 같은 정의로 내고 화면은 반올림만 한다. n < 5면 "표본 적음" 배지.
  버전 색은 차트와 같은 번호 규칙(차트에 보이는 버전과 합쳐 고른다)이고 견본에만 쓰며 글자는 잉크색이다.
  평가가 없으면, 같은 날 추천과 trip이 있는 날이 있을 때만 `wio-analytics evaluate`를 돌리라고 안내한다(운영은 새벽 03:00 cron).
  표시 로직은 `src/recommendations/evaluations.ts`

## 지도 경로 편집과 GPS 트랙 (#64)

- 지도는 Leaflet + OpenStreetMap 기본 타일(`tile.openstreetmap.org`), 오른쪽 아래에 OSM 출처 표기. leaflet(JS·CSS)은
  `src/map/MapView.tsx` 한 파일만 import하고 `LazyMap`이 lazy로 읽으므로 지도가 있는 화면에서만 따로 받는 청크다.
  마커는 이미지 대신 CSS 핀(`.map-pin-*`, 다크 모드 토큰 포함)이고 타일 자체는 다크 모드에서도 기본 색 그대로다
- `/routes/new`: 이름·방향을 넣고 지도를 눌러 출발(집)·도착을 찍는다(마커는 끌어서 옮김). `POST /commute-routes` 후 구간 편집으로 간다
- `/routes/:id/edit` (경로 탭 "구간 편집"): 구간 카드 목록 + 지도. `PUT /commute-routes/{id}/legs`로 **전체 교체**하고
  저장된 구간은 `id`를 붙여 보낸다(재정렬해도 실측 기록·crossing 유지, API.md "구간 교체의 의미"). 구간이 없는 경로는
  출발 → 도착 도보 한 구간으로 시작한다
  - WALK: 끝점을 지도에서 끌거나 "지도에서 찍기". 계획 거리는 직선거리(대권거리)로 채우고, 직접 고친 값은 끝점을 옮겨도 그대로 둔다
    ("직선거리로"로 되돌림). "뒤에 대중교통 넣기"는 도보 하나를 도보 → 대중교통 → 도보로 쪼갠다
  - TRANSIT: `/transit-lines/search`로 노선을 고르고, 승차·하차 정류장은 `/transit-stops/nearby`(노선과 같은 수단, 800 m)에서
    고른다. 기준점은 앞 도보의 끝 / 뒤 도보의 시작, 또는 지도에서 찍은 곳. 비어 있던(또는 예전 정류장에 붙어 있던) 옆 도보 끝점은
    고른 정류장으로 옮긴다. 노선별 정류장 목록 API는 없어서 근처 검색만 쓴다
  - 저장 전에 서버 규칙을 먼저 검사한다(`src/legs/legRules.ts`: `RouteLegService`·`RouteLegRequest` 검증을 그대로 옮김 —
    서버를 바꾸면 같이 바꾼다). 서버만 아는 것(실측 기록이 붙은 구간 삭제 → 409 등)은 `detail`을 그대로 보여 준다
  - 신호등: 선택한 **저장된** 도보 구간에서 근처 교차로(`/traffic-signals/nearby`, 구간을 덮는 원)를 목록·지도 마커로 골라
    순서·접근 방향·신호 종류를 정하고 `PUT /route-legs/{id}/signal-crossings`로 따로 저장한다. "지도에서 교차로 등록"은
    지도를 누른 자리에 `POST /traffic-signals`로 만들고 바로 추가한다
- 경로 상세에 읽기 전용 지도(도보 선, 대중교통 점선과 승하차 정류장)
- trip 상세 "GPS 트랙": `GET /commute-trips/{id}/gps-traces`를 시각순 폴리라인으로 그리고 시작/끝, 경로의 승하차 정류장(그 trip의
  정류장 도착·하차 시각)을 마커로 단다
- 화면 테스트는 `src/test/setup.ts`에서 `MapView`를 `src/test/MockMap.tsx`(마커·선 목록 + 클릭/끌기 버튼)로 바꾼다

## 경로별 기본 목표 도착 시각 (#68)

- `/routes/new`와 경로 상세의 "설정" 폼에서 **목표 도착 시각(KST, `HH:mm`)**과 **추천할 날**(평일 / 토요일 /
  일요일·공휴일 체크박스, 기본 평일)을 정한다. analytics `recommend --all-active-routes`(cron 한 줄)가 사용 중인 경로마다
  이 시각의 −120분 ~ +30분 동안 추천을 다시 계산한다. 시각을 비우면 그 경로는 일괄 추천에서 빠진다
- 설정 폼은 이름·사용 여부도 고친다. `PATCH /commute-routes/{id}`로 **바뀐 필드만** 보내고(서버는 `null`을 "그대로"로 본다),
  시각을 지울 때는 `clearDefaultTargetArrivalTime: true`를 보낸다. 날을 하나도 고르지 않으면 보내기 전에 막는다
  (서버도 400). 규칙은 `src/target/defaultTarget.ts`(순수 로직), 입력 칸은 `pages/TargetFields.tsx`
- 저장 응답을 상세 캐시의 `route`에 바로 넣고 목록·상세를 다시 받는다

## 정적 시간표 (#70)

- `/schedules` (상단 "시간표"): GTX처럼 실시간 API가 없는 노선의 시간표 CSV를 `POST /admin/schedules/import`(multipart
  파트 `file`)로 올리고 `GET /transit-lines/{id}/schedules/next`로 확인한다. 형식은 [docs/API.md](../docs/API.md#정적-시간표)
- 파일을 고르면 **올리기 전에** 브라우저에서 서버와 같은 규칙으로 검사한다(`src/schedules/csv.ts`: `TransitScheduleService`
  `parseRows`/`parseRow`와 `ScheduleAdminController`를 그대로 옮김 — 서버를 바꾸면 같이 바꾼다). 오류 메시지는 서버 `detail`과
  같은 문자열(`row 2: expected 5 columns …`)이고 줄 원문을 같이 보여 준다. Kotlin `trim()`/`toLongOrNull()`/`LocalTime.parse`의
  세부(U+FEFF는 칸 안에서 공백이 아님, `+12`·유니코드 숫자 허용, `07:30:05.` 허용, `24:00` 거부 등)까지 맞췄다
- 통과하면 미리보기(데이터 행·넣을 차편·파일 안 중복, 노선/정류장/방향/day_type별 행 수)와 **교체 범위**(파일에 나온
  (노선, 정류장, day_type, 방향) 조합마다 기존 행을 지우고 바꾼다)를 표로 보여 주고, 확인 체크를 해야 업로드 버튼이 켜진다
- 서버 결과(`fetched/created/updated/skipped`)와 거절(`400` `detail`, 예: `row 3: unknown transit_stop_id 999999`)을 그대로 보여 준다.
  없는 노선·정류장 id는 서버만 안다
- 업로드 요청: 생성 타입은 binary 파트를 `string`으로 두므로 본문은 타입만 맞추고 `bodySerializer`가 File을 FormData에 담는다
  (`useImportSchedules`, Content-Type·boundary는 브라우저가 붙인다)
- "다음 출발": 노선 검색(`LinePicker`, 구간 편집과 공용) → 정류장 id(방금 고른 CSV와 내 경로 구간의 정류장이 후보) → 방향 코드
  → 기준 시각(KST) → 대수(1~50). 업로드에 성공하면 그 파일의 첫 조합으로 칸을 채운다. 다음 날로 넘어간 차편은 "다음 날"로 표시
- 노선·정류장 단건 조회 API가 없어서 이름은 내 경로의 TRANSIT 구간에서 아는 것만 쓰고 나머지는 `#id`로 보인다

## 운영 (#76)

- `/ops`: 머리 메뉴 "운영". `GET /admin/ops/external-apis` 하나로 그리고 **30초마다** 다시 받는다(`OPS_REFRESH_MS`).
  새로 고침이 실패하면 받아 둔 값을 그대로 두고 위에 알린다
- 위: 폴링(켜짐/꺼짐, 창과 지금 창 안인지, 마지막 사이클 시각·결과·일부 실패 코드)과 보관 정리(켜짐/꺼짐, dry-run, cron,
  다음 실행, 마지막 실행의 테이블별 행 수) 카드
- 아래: 소스(TAGO/KLID)마다 op별 표 — 오늘(KST)·최근 1시간의 호출 수, 실패율(+ 결과별 실패 내역), p50/p95, 일 한도 사용률 막대.
  단위는 HTTP 시도(재시도 포함)
- 강조 규칙(`src/ops/ops.ts`): 오늘이나 최근 1시간 실패율이 20% 이상이면 행 배경 + "실패율 높음". 한도 막대는 70% 이상
  노랑 "한도 70%+", 90% 이상 빨강 "한도 90%+"(ADR 0002의 재검토 기준과 같은 70%). 상태 색은 고정 팔레트이고 언제나
  아이콘 + 글자와 같이 나온다 — 색만으로 뜻을 싣지 않는다. 막대의 빈 칸은 채움과 같은 계열의 옅은(다크에서는 어두운) 색
- 서버 집계는 메모리라 재시작하면 0부터다. 화면 위에 "집계 시작" 시각을 보여 주고, 기록이 하나도 없으면 빈 상태 문구를 띄운다

## 구조

```
src/
  api/        schema.d.ts(생성), client.ts(openapi-fetch + 토큰/401 미들웨어), queries.ts(TanStack Query 훅)
  auth/       token.ts(localStorage), RequireAuth.tsx
  pages/      LoginPage, Layout, RouteListPage, RouteDetailPage,
              TripListPage, TripDetailPage(+ TripTimeline, TripTimesForm, AttemptForm),
              RouteCreatePage, RouteEditPage(+ LegCards, SignalCrossingsEditor), TripGpsMap,
              RouteSettingsForm(+ TargetFields),
              RouteCalibrationPage(+ RouteTabs), RouteRecommendationsPage(+ RecommendationCharts lazy, EvaluationSummary),
              SchedulesPage(+ ScheduleUpload, NextDeparturesChecker), LinePicker(노선 검색, 공용), OpsPage(운영)
  calibration/ chain.ts(보정값 조회 순서·기본값 — analytics lookup.py를 옮김) — 화면 없는 순수 로직
  recommendations/ history.ts(추천 vs 실제: 차이·지각·차트 계열·축·버전 색), evaluations.ts(버전별 성과 tile) — 화면 없는 순수 로직
  legs/       legRules.ts(구간 초안 ↔ PUT 본문, 서버와 같은 검증, crossing 코드) — 화면 없는 순수 로직
  map/        MapView.tsx(leaflet, lazy 청크), LazyMap.tsx, geo.ts(대권거리·범위), overlay.ts(경로·GPS 마커/선)
  target/     defaultTarget.ts(기본 목표 도착 시각·추천할 날 초안 ↔ 생성 필드/PATCH 본문, 검증) — 화면 없는 순수 로직
  schedules/  csv.ts(시간표 CSV 검사·미리보기 — 서버 파서를 옮김), next.ts(다음 출발 조회 파라미터·후보) — 화면 없는 순수 로직
  trips/      timeline.ts(타임라인·결과 요약·예측 오차), corrections.ts(보정 폼 → PATCH 본문) — 화면 없는 순수 로직
  ops/        ops.ts(운영 화면: 실패율 강조·한도 단계·표시 형식) — 화면 없는 순수 로직
  kst.ts      datetime-local ↔ UTC ISO. 브라우저 시간대와 상관없이 KST(UTC+9)로 읽고 쓴다
  routes.tsx  라우트 표
  context.ts  라우터 · QueryClient · API 클라이언트 묶음 (테스트는 메모리 라우터와 가짜 fetch를 넣는다)
```
