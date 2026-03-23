import { Redirect, Route, Switch } from "wouter";

import { LoginPage } from "@/features/auth/pages/LoginPage";
import { InitialLandingPage } from "@/features/auth/pages/InitialLandingPage";
import { SignupPage } from "@/features/auth/pages/SignupPage";
import { BankAccountsPage } from "@/features/bank-accounts/pages/BankAccountsPage";
import { PendingReviewPage } from "@/features/payments/pages/PendingReviewPage";
import { TripsAdminPage } from "@/features/trips/pages/TripsAdminPage";
import { SpreadsheetPage } from "@/features/trips/pages/SpreadsheetPage";
import { UserDashboardPage } from "@/features/users/pages/UserDashboardPage";
import { getRoleFromToken } from "@/lib/auth-role";
import { useToken } from "@/lib/session";

export function AppRoutes() {
  const [tokenState] = useToken();

  switch (tokenState.state) {
    case "LOGGED_IN": {
      const role = getRoleFromToken(tokenState.accessToken);

      if (role === "ADMIN") {
        return (
          <Switch>
            <Route path="/">
              <TripsAdminPage />
            </Route>
            <Route path="/trips/:id/spreadsheet">
              {(params) => <SpreadsheetPage tripId={Number(params.id)} />}
            </Route>
            <Route path="/payments/pending-review">
              <PendingReviewPage />
            </Route>
            <Route path="/bank-accounts">
              <BankAccountsPage />
            </Route>
            <Route>
              <Redirect href="/" />
            </Route>
          </Switch>
        );
      }

      if (role === "USER") {
        return (
          <Switch>
            <Route path="/">
              <UserDashboardPage />
            </Route>
            <Route>
              <Redirect href="/" />
            </Route>
          </Switch>
        );
      }

      return (
        <Switch>
          <Route path="/">
            <UserDashboardPage />
          </Route>
          <Route>
            <Redirect href="/" />
          </Route>
        </Switch>
      );
    }

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

