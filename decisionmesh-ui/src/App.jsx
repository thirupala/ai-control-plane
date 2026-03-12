import { useState } from "react";
import { ThemeProvider } from "@mui/material/styles";
import { getTheme } from "./theme";
import DashboardLayout from "./layout/DashboardLayout";
import PlaygroundPage from "./pages/PlaygroundPage";
import IntentViewerPage from "./pages/IntentViewerPage";
import HomePage from "./pages/HomePage";

function App({ keycloak }) {
  //  All hooks must be called before any conditional return
  const [page, setPage] = useState("playground");
  const [darkMode, setDarkMode] = useState(true);

  // Conditional render AFTER hooks
  if (!keycloak.authenticated) {
    return <HomePage keycloak={keycloak} />;
  }

  const roles = keycloak.tokenParsed?.realm_access?.roles || [];

  const renderPage = () => {
    switch (page) {
      case "viewer":
        return <IntentViewerPage keycloak={keycloak} />;  //  pass keycloak not token
      default:
        return <PlaygroundPage keycloak={keycloak} />;
    }
  };

  return (
      <ThemeProvider theme={getTheme(darkMode ? "dark" : "light")}>
        <DashboardLayout
            keycloak={keycloak}
            roles={roles}
            onNavigate={setPage}
            toggleTheme={() => setDarkMode(!darkMode)}
            darkMode={darkMode}
        >
          {renderPage()}
        </DashboardLayout>
      </ThemeProvider>
  );
}

export default App;
