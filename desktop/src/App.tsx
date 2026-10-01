import { QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "react-router";
import { ApiProvider } from "./api/ApiContext";
import type { AppContext } from "./context";

export function App({ context }: { context: AppContext }) {
  return (
    <ApiProvider client={context.api}>
      <QueryClientProvider client={context.queryClient}>
        <RouterProvider router={context.router} />
      </QueryClientProvider>
    </ApiProvider>
  );
}
