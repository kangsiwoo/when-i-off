import react from "@vitejs/plugin-react";
import { loadEnv } from "vite";
import { defineConfig } from "vitest/config";

// 브라우저는 항상 같은 출처의 `/api/...`로 요청하고, 개발/미리보기 서버가 VITE_API_BASE(backend)로
// 넘긴다. 그래서 backend에 CORS 설정이 필요 없다. 배포할 때도 같은 방식(리버스 프록시)으로 둔다.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "VITE_");
  const target = env.VITE_API_BASE || "http://localhost:8080";
  const proxy = { "/api": { target, changeOrigin: true } };

  return {
    plugins: [react()],
    // 글꼴 조각은 data: URI로 CSS에 넣지 않는다 (unicode-range로 필요할 때만 받게).
    build: { assetsInlineLimit: (file: string) => (file.endsWith(".woff2") ? false : undefined) },
    // 토큰 검사(src/styles/tokens.test.ts)만 레포의 site/style.css를 읽는다. 개발 서버는 그대로 desktop/ 안만 연다.
    server: mode === "test" ? { proxy, fs: { allow: [".", "../site"] } } : { proxy },
    preview: { proxy },
    test: {
      environment: "jsdom",
      globals: true,
      setupFiles: ["./src/test/setup.ts"],
      restoreMocks: true,
      // CSS는 기본으로 빈 모듈이 된다. 토큰 검사가 `?raw`로 읽는 두 파일만 실제 내용을 받는다.
      css: { include: [/src\/styles\/tokens\.css/, /site\/style\.css/] },
    },
  };
});
