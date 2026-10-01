import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter } from "react-router";
import { App } from "./App";
import { createAppContext } from "./context";
import "./index.css";

const context = createAppContext({ createRouter: (routes) => createBrowserRouter(routes) });

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App context={context} />
  </StrictMode>,
);
