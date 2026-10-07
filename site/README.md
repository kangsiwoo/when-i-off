# site — 서비스 소개 페이지

서울 열린데이터광장 지하철 실시간 도착정보 키를 신청할 때 **활용사례**에 넣을 공개 URL용 정적
랜딩 페이지다 (#92). 활용사례에는 실제로 열리는 앱/웹 주소가 필요하고 GitHub·Notion·localhost는
반려된다. 빌드 단계 없는 순수 정적 파일이라 Cloudflare Pages에 그대로 올린다.

```
site/
  index.html          소개 페이지 (출발 안내판 컨셉)
  privacy.html        개인정보 처리 안내
  style.css           라이트(종이 안내판)/다크(prefers-color-scheme), 인라인 스타일 없음. 맨 위 토큰 블록은
                      desktop/src/styles/tokens.css의 사본 (아래 "디자인 토큰")
  app.js              split-flap 애니메이션, 출발 시각 슬라이더·확률 곡선(설명용 합성 모델)
  _headers            Cloudflare Pages 보안 헤더 (CSP 등)
  assets/
    whenioff_logo.svg / whenioff_logo.png (140x140)   활용사례 "로고 이미지"
    whenioff_screen.png (700x700)                      활용사례 "화면 이미지"
    favicon-32.png, apple-touch-icon.png (180x180)    whenioff_logo.svg에서 렌더링
    screen_recommendations.jpg (920x920), screen_route.jpg, screen_trip.jpg (1100x1100)   본문 스크린샷
    fonts/
      wio-sans.woff2            Pretendard 1.3.9 가변 글꼴 서브셋 (OFL, 이름 변경 — 아래 참고)
      jetbrains-mono-wio.woff2  JetBrains Mono 가변 글꼴 서브셋 (OFL)
      LICENSE-*.txt             두 글꼴의 OFL 전문
```

이 README도 `site/` 안에 있어 배포 시 `/README.md`로 열린다(`_headers`에서 `noindex`). 비밀값은 없다.

## Cloudflare 배포 설정

Cloudflare 대시보드의 기본 생성 흐름은 이제 **Workers**(정적 자산)다. 레포 루트의 `wrangler.jsonc`가
`site/`를 자산 디렉터리로 지정하므로 Workers와 Pages 어느 쪽으로도 올릴 수 있다.

**Workers (기본 흐름)** — Workers & Pages → Create → Import a repository → `kangsiwoo/when-i-off`

| 항목 | 값 |
|---|---|
| Project name | `when-i-off` |
| Build command | (비움) |
| Deploy command | `npx wrangler deploy` (기본값) |

주소는 `https://when-i-off.<계정 서브도메인>.workers.dev`. `_headers`는 Workers 정적 자산에도 적용되고,
`site/.assetsignore`가 이 README를 업로드에서 뺀다.

**Pages (대안)** — Create 화면 아래의 Pages 링크 → Connect to Git

| 항목 | 값 |
|---|---|
| Production branch | `main` |
| Framework preset | `None` |
| Build command | (비움) |
| Build output directory | `site` |

주소는 `https://<project>.pages.dev`.

어느 쪽이든 `main`에 머지될 때마다 자동 배포된다. 커스텀 도메인은 나중에 붙인다.

로컬 확인: `npx serve site` (또는 `python3 -m http.server -d site`). `_headers`는 Cloudflare에서만 적용된다.

## 외부 요청 없음 · CSP

글꼴·스크립트·이미지 전부 이 사이트에서 제공하고 런타임 외부 요청이 없다. CSP는
`script-src 'self'; style-src 'self'; font-src 'self'`이므로 **인라인 `<script>`, `style="..."` 속성,
`on*=` 핸들러를 쓰면 막힌다.** 스크립트는 `app.js`, 스타일은 `style.css`에 둔다
(JS에서 `el.style.x = ...`로 바꾸는 것은 허용된다). `prefers-reduced-motion`이면 split-flap·LED 깜빡임을 끈다.

## 디자인 토큰

색·글자 크기·간격·규칙선·모서리 토큰의 기준은 **`desktop/src/styles/tokens.css`** 다 (#96). 데스크톱 앱이 같은 디자인
시스템을 쓰고, 이 사이트는 빌드 단계가 없어 그 파일을 import할 수 없으므로 `style.css` 맨 위 `:root` 블록(라이트)과
`prefers-color-scheme: dark` 블록에 같은 값을 옮겨 둔다. `desktop/src/styles/tokens.test.ts`가 두 파일의 값이 같은지 검사하고
(사이트 전용 `--sans` `--mono` `--wrap` `--gutter` `--col-gap`만 예외), desktop CI는 `site/style.css`가 바뀌어도 돈다.
토큰을 바꿀 때는 두 파일을 같이 바꾼다.

03 섹션의 승차권 예시(FIG. B)는 데스크톱 앱 이동 기록의 승차권(`desktop/src/pages/TripTicket.tsx`)과 같은 모양을 HTML/CSS로만
그린 것이다. 값은 설명용 합성 값이고 화면에 "예시"로 적혀 있다.

## 글꼴 서브셋

글꼴은 페이지에 실제로 쓰인 글자(한글 약 330자) + 기본 라틴만 남긴 서브셋이다 (합계 약 90KB).
**`index.html`/`privacy.html`/`app.js`에 새 한글을 넣으면 서브셋을 다시 만든다** — 빠진 글자는 시스템
글꼴로 대체되어 모양이 섞인다.

```sh
mkdir -p /tmp/fonts && cd /tmp/fonts
npm pack pretendard@1.3.9 @fontsource-variable/jetbrains-mono@5.3.0
mkdir -p pre jbm && tar xzf pretendard-1.3.9.tgz -C pre && tar xzf fontsource-variable-jetbrains-mono-5.3.0.tgz -C jbm
cd -  # 레포 루트
pip install fonttools brotli
python3 scripts/site-subset-fonts.py \
  /tmp/fonts/pre/package/dist/web/variable/woff2/PretendardVariable.woff2 \
  /tmp/fonts/jbm/package/files/jetbrains-mono-latin-wght-normal.woff2
```

Pretendard는 OFL의 **예약 글꼴 이름(Reserved Font Name)** 이 있어 수정본(서브셋)에 `Pretendard` 이름을 쓸 수
없다. 그래서 스크립트가 이름 테이블을 `WIO Sans`로 바꾸고 CSS에서도 그 이름으로 쓴다. 저작권 표기와 라이선스는
그대로 두고 `assets/fonts/LICENSE-Pretendard.txt`를 함께 배포한다.

## 로고 PNG

`whenioff_logo.svg`가 원본이다. PNG는 Chromium으로 SVG를 그대로 렌더링해 만든다:
`whenioff_logo.png` 140x140(모서리 투명), `favicon-32.png` 32x32, `apple-touch-icon.png` 180x180(모서리 없는 정사각형,
iOS가 직접 마스크를 씌운다). 열린데이터광장 양식은 로고 140x140을 요구하니 크기를 바꾸지 않는다.

## 스크린샷 다시 찍기

화면 이미지는 **개발 DB(`when_i_off`)가 아니라 테스트 DB(`when_i_off_test`)** 에 공개 장소 기준 합성 데이터
(동탄역 → GTX-A → 수서역)를 API로 넣고 찍었다. 개발 DB에는 실제 집·회사 위치가 있을 수 있으니 쓰지 않는다.
화면은 소개 페이지의 종이 테마와 맞춰 **라이트**로 찍는다.

1. 테스트 DB의 테이블별 행 수를 적어 둔다 (끝나고 같아야 한다)
2. backend를 테스트 DB로 띄운다: `WIO_DB_URL=jdbc:postgresql://localhost:5432/when_i_off_test WIO_DB_USER=wio WIO_DB_PASSWORD=wio WIO_API_TOKEN=<임의> java -jar backend/build/libs/*.jar`
3. API로 경로·구간·신호등·trip(평일 15일, 그중 이틀은 첫 차를 놓침)·attempt·GPS를 넣고, analytics `derive-walking-segments` →
   `calibrate` → `recommend --route-id <id> --target-arrival-at <날짜>T09:00`(평일마다) → `evaluate --from --to`를 같은 DB로 돌린다
4. `desktop`에서 `npm run dev`, Playwright(Chromium)로 `localStorage['wio.apiToken']`을 넣고 라이트 테마로 캡처한다
   - `whenioff_screen.png`: `/routes/<id>/recommendations` 를 CSS 880px 창에서 `deviceScaleFactor` 700/880로 — **정확히 700x700**
   - `screen_recommendations.jpg`: 같은 화면, 1150px 창 × 0.8 = 920x920
   - `screen_route.jpg`: `/routes/<id>`, 지도가 아래 끝에 오게 1100x1100 (페이지는 16:10 아래쪽을 잘라 보여 준다)
   - `screen_trip.jpg`: `/trips/<id>?routeId=&date=` 위에서부터 1100x1100 (승차권 + 타임라인, 첫 차를 놓친 날)
   - OSM 타일은 출처 표기를 그대로 둔 채 찍는다 (페이지 04 아래에도 출처를 적는다)
5. 넣은 행을 지우고 1의 행 수와 비교한다. 평가·추천(`commute_route_id`) → 전역 도보 프로필(`route_leg_id IS NULL`) → 경로(trip·attempt·구간·
   구간별 프로필·도보 실측·crossing이 캐스케이드) → 신호등(주기 캐스케이드) → 대중교통 보정 두 테이블 순. GPS 포인트는 trip을 지워도
   `commute_trip_id`만 비므로 따로 지운다

## 활용사례 등록 문구 (초안)

서울 열린데이터광장 → 활용사례 등록. 복사해서 쓰고 URL만 실제 배포 주소로 바꾼다.

- **제목**: When I Off — 지각 확률로 알려주는 출근 출발 시각 추천
- **URL**: `https://<project>.pages.dev`
- **활용 데이터**: 서울시 지하철 실시간 도착정보
- **로고 이미지**: `site/assets/whenioff_logo.png` (140x140)
- **화면 이미지**: `site/assets/whenioff_screen.png` (700x700)

**설명**

> 광역버스·GTX·지하철처럼 한 대 놓치면 15~30분을 기다려야 하는 출근길에서 "몇 시에 나가야 제시간에
> 도착하는가"를 계산해 주는 개인 프로젝트입니다. 버스·지하철 실시간 도착정보와 사용자가 실제로 탔던·놓쳤던
> 기록, 개인 도보 속도, 횡단보도 신호 대기를 합쳐 출발 시각별 제시간 도착 확률을 구하고, 추천 시각에
> "지금 나가세요 (성공 확률 P%)" 알림을 보냅니다. 서울시 지하철 실시간 도착정보는 지하철 구간의 열차
> 도착 예측을 수집해 탑승 기록과의 오차를 보정하는 데 사용합니다.

**주요 기능**

> 1. 출퇴근 경로 등록 — 지도에서 도보·버스·지하철·GTX 구간과 경유 횡단보도 등록
> 2. 자동 탑승 기록 — iPhone 앱이 정류장·역 geofence로 도착/출발 시각을 기록하고 "탔음/놓쳤음" 버튼으로 결과 기록
> 3. 실시간 도착정보 수집 — 출퇴근 시간대에 버스(국토교통부 TAGO)·지하철(서울시 실시간 도착정보) 도착 예측과 신호등 정보 수집
> 4. 출발 시각 추천 — 구간별 소요 시간 분포를 합성해 목표 도착 시각 기준 지각 확률을 계산, 출발 알림
> 5. 추천 vs 실제 비교 — 데스크톱 웹에서 날짜별 추천 출발과 실제 출발·도착, 지각 여부와 모델 성과 확인
