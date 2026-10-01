import { Navigate, type RouteObject } from "react-router";
import { RequireAuth } from "./auth/RequireAuth";
import { Layout } from "./pages/Layout";
import { LoginPage } from "./pages/LoginPage";
import { RouteDetailPage } from "./pages/RouteDetailPage";
import { RouteListPage } from "./pages/RouteListPage";
import { TripDetailPage } from "./pages/TripDetailPage";
import { TripListPage } from "./pages/TripListPage";

export const routes: RouteObject[] = [
  { path: "/login", element: <LoginPage /> },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <Layout />,
        children: [
          { index: true, element: <RouteListPage /> },
          { path: "routes/:id", element: <RouteDetailPage /> },
          { path: "trips", element: <TripListPage /> },
          { path: "trips/:id", element: <TripDetailPage /> },
        ],
      },
    ],
  },
  { path: "*", element: <Navigate to="/" replace /> },
];
