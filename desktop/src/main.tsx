import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter } from "react-router";
// 글꼴은 번들에 넣는다 (런타임 CDN 없음, #96). Pretendard는 수정하지 않은 원본(OFL)의 dynamic subset이라
// 화면에 나온 글자가 속한 조각만 받는다. JetBrains Mono도 unicode-range로 라틴 조각만 받는다.
import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
import "@fontsource-variable/jetbrains-mono/wght.css";
import { App } from "./App";
import { createAppContext } from "./context";
import "./styles/tokens.css";
import "./index.css";

const context = createAppContext({ createRouter: (routes) => createBrowserRouter(routes) });

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App context={context} />
  </StrictMode>,
);
