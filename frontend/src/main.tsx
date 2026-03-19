import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { App } from "@/App";
import { configureZodSpanishErrors } from "@/lib/zod-error-map-es";
import type {} from "@/WindowEnv";

import "./index.css";

configureZodSpanishErrors();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
