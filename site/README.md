# site — 서비스 소개 페이지

서울 열린데이터광장 지하철 실시간 도착정보 키를 신청할 때 **활용사례**에 넣을 공개 URL용 정적
랜딩 페이지다 (#92). 활용사례에는 실제로 열리는 앱/웹 주소가 필요하고 GitHub·Notion·localhost는
반려된다. 빌드 단계 없는 순수 정적 파일이라 Cloudflare Pages에 그대로 올린다.

```
site/
  index.html          소개 페이지
  privacy.html        개인정보 처리 안내
  style.css           라이트/다크 (prefers-color-scheme), 외부 폰트·스크립트 없음
  _headers            Cloudflare Pages 보안 헤더 (CSP 등)
  assets/
    whenioff_logo.svg / whenioff_logo.png (140x140)   활용사례 "로고 이미지"
    whenioff_screen.png (700x700)                      활용사례 "화면 이미지"
    favicon-32.png, apple-touch-icon.png
    screen_recommendations.jpg, screen_route.jpg, screen_trip.jpg   본문 스크린샷
```

이 README도 `site/` 안에 있어 배포 시 `/README.md`로 열린다(`_headers`에서 `noindex`). 비밀값은 없다.

## Cloudflare Pages 설정

대시보드 → Workers & Pages → Create → Pages → **Connect to Git** → `kangsiwoo/when-i-off`

| 항목 | 값 |
|---|---|
| Production branch | `main` |
| Framework preset | `None` |
| Build command | (비움) |
| Build output directory | `site` |
| Root directory (advanced) | (비움 = 레포 루트) |
| 환경변수 | 없음 |

배포되면 `https://<project>.pages.dev`가 생긴다. 프로젝트 이름을 `when-i-off`로 하면
`when-i-off.pages.dev`(이미 쓰였으면 접미사가 붙는다). 커스텀 도메인은 나중에 Custom domains에서 붙인다.
`main`에 머지될 때마다 자동 배포되고, 다른 브랜치는 Preview 배포가 된다(필요 없으면
Settings → Builds → Branch control에서 끈다). `site/`와 무관한 커밋도 다시 배포되는데 정적 파일 복사뿐이라 문제 없다
(줄이고 싶으면 Build watch paths에 `site/*`).

로컬 확인: `npx serve site` (또는 `python3 -m http.server -d site`). `_headers`는 Cloudflare에서만 적용된다.

## 스크린샷 다시 찍기

화면 이미지는 **개발 DB(`when_i_off`)가 아니라 테스트 DB(`when_i_off_test`)** 에 공개 장소 기준 합성 데이터
(동탄역 → GTX-A → 수서역)를 API로 넣고 찍었다. 개발 DB에는 실제 집·회사 위치가 있을 수 있으니 쓰지 않는다.

1. backend를 테스트 DB로 띄운다: `WIO_DB_URL=jdbc:postgresql://localhost:5432/when_i_off_test WIO_DB_USER=wio WIO_DB_PASSWORD=wio WIO_API_TOKEN=<임의> java -jar backend/build/libs/*.jar`
2. API로 경로·구간·trip·attempt·GPS를 넣고, analytics `derive-walking-segments` → `calibrate` → `recommend` → `evaluate`를 같은 DB로 돌린다
3. `desktop`에서 `npm run dev`, Playwright로 `localStorage['wio.apiToken']`을 넣고 `/routes/<id>/recommendations` 등을 캡처
4. 넣은 행을 지운다 (테스트 DB는 통합 테스트가 `flyway clean`하므로 남아도 다음 테스트 때 사라지지만, 바로 지운다)

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
