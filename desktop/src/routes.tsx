import { Navigate, type RouteObject } from "react-router";
import { RequireAuth } from "./auth/RequireAuth";
import { Layout } from "./pages/Layout";
import { LoginPage } from "./pages/LoginPage";
import { RouteCalibrationPage } from "./pages/RouteCalibrationPage";
import { RouteCreatePage } from "./pages/RouteCreatePage";
import { RouteDetailPage } from "./pages/RouteDetailPage";
import { RouteEditPage } from "./pages/RouteEditPage";
import { RouteListPage } from "./pages/RouteListPage";
import { RouteRecommendationsPage } from "./pages/RouteRecommendationsPage";
import { SchedulesPage } from "./pages/SchedulesPage";
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
          { path: "routes/new", element: <RouteCreatePage /> },
          { path: "routes/:id", element: <RouteDetailPage /> },
          { path: "routes/:id/edit", element: <RouteEditPage /> },
          { path: "routes/:id/calibration", element: <RouteCalibrationPage /> },
          { path: "routes/:id/recommendations", element: <RouteRecommendationsPage /> },
          { path: "trips", element: <TripListPage /> },
          { path: "trips/:id", element: <TripDetailPage /> },
          { path: "schedules", element: <SchedulesPage /> },
        ],
      },
    ],
  },
  { path: "*", element: <Navigate to="/" replace /> },
];
