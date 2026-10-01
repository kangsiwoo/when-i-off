# desktop

TypeScript + React(Vite) 관리·조회 웹. 지금은 토큰 로그인 → 경로 목록 → 경로 상세(구간 목록)까지 있다 (#56).
지도 위 경로/구간/정류장/신호등 편집, trip 히스토리, 캘리브레이션 상태, 추천 vs 실제 비교는 이 골격 위에 올린다.
상세는 [docs/DEVELOPMENT_PLAN.md](../docs/DEVELOPMENT_PLAN.md) Phase 5, 이슈 #7.

스택: Vite · React 18 · TypeScript(strict) · TanStack Query · react-router · openapi-fetch,
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

## 구조

```
src/
  api/        schema.d.ts(생성), client.ts(openapi-fetch + 토큰/401 미들웨어), queries.ts(TanStack Query 훅)
  auth/       token.ts(localStorage), RequireAuth.tsx
  pages/      LoginPage, Layout, RouteListPage, RouteDetailPage
  routes.tsx  라우트 표
  context.ts  라우터 · QueryClient · API 클라이언트 묶음 (테스트는 메모리 라우터와 가짜 fetch를 넣는다)
```
