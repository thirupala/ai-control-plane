// src/app/routes.jsx
import { createBrowserRouter } from "react-router-dom";
import AppLayout from "../components/layout/AppLayout";
import Dashboard from "../pages/Dashboard";
import Playground from "../pages/Playground";
import Intents from "../pages/Intents";
import Adapters from "../pages/Adapters";

export const router = createBrowserRouter([
    {
        path: "/",
        element: <AppLayout />,
        children: [
            { index: true, element: <Dashboard /> },
            { path: "playground", element: <Playground /> },
            { path: "intents", element: <Intents /> },
            { path: "adapters", element: <Adapters /> },
        ],
    },
]);