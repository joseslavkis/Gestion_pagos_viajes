import { Redirect, Route, Switch } from "wouter";

import { LoginPage } from "@/features/auth/pages/LoginPage";
import { InitialLandingPage } from "@/features/auth/pages/InitialLandingPage";
import { SignupPage } from "@/features/auth/pages/SignupPage";
import { MainScreen } from "@/features/trips/pages/MainScreen";
import { useToken } from "@/lib/session";

export function AppRoutes() {
  const [tokenState] = useToken();

  switch (tokenState.state) {
    case "LOGGED_IN":
      return (
        <Switch>
          <Route path="/">
            <MainScreen />
          </Route>
          <Route>
            <Redirect href="/" />
          </Route>
        </Switch>
      );

    case "LOGGED_OUT":
      return (
        <Switch>
          <Route path="/">
            <InitialLandingPage />
          </Route>
          <Route path="/login">
            <LoginPage />
          </Route>
          <Route path="/signup">
            <SignupPage />
          </Route>
          <Route>
            <Redirect href="/" />
          </Route>
        </Switch>
      );

    default:
      return tokenState satisfies never;
  }
}

