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
    server: { proxy },
    preview: { proxy },
    test: {
      environment: "jsdom",
      globals: true,
      setupFiles: ["./src/test/setup.ts"],
      restoreMocks: true,
    },
  };
});
