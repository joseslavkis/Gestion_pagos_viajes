import { type ReactNode, useRef } from "react";
import { Redirect, Route, Switch, useLocation } from "wouter";

import { ForgotPasswordPage } from "@/features/auth/pages/ForgotPasswordPage";
import { LoginPage } from "@/features/auth/pages/LoginPage";
import { InitialLandingPage } from "@/features/auth/pages/InitialLandingPage";
import { ResetPasswordPage } from "@/features/auth/pages/ResetPasswordPage";
import { SignupPage } from "@/features/auth/pages/SignupPage";
import { BankAccountsPage } from "@/features/bank-accounts/pages/BankAccountsPage";
import { PendingReviewPage } from "@/features/payments/pages/PendingReviewPage";
import { SchoolsPage } from "@/features/schools/pages/SchoolsPage";
import { TripsAdminPage } from "@/features/trips/pages/TripsAdminPage";
import { SpreadsheetPage } from "@/features/trips/pages/SpreadsheetPage";
import { AdminUserDetailPage } from "@/features/users/pages/AdminUserDetailPage";
import { AdminUserSearchPage } from "@/features/users/pages/AdminUserSearchPage";
import { StudentsPage } from "@/features/users/pages/StudentsPage";
import { UserDashboardPage } from "@/features/users/pages/UserDashboardPage";
import { getRoleFromToken } from "@/lib/auth-role";
import { createGsapMatchMedia, getMotionProfile, gsap, useGSAP } from "@/lib/gsap";
import { useToken } from "@/lib/session";

export function AppRoutes() {
  const [location] = useLocation();
  const [tokenState] = useToken();
  const routeShellRef = useRef<HTMLDivElement | null>(null);

  useGSAP(
    () => {
      const shell = routeShellRef.current;
      if (!shell) {
        return;
      }

      const motion = getMotionProfile();
      const matchMedia = createGsapMatchMedia();

      if (!matchMedia) {
        gsap.set(shell, { clearProps: "opacity,visibility,transform" });
        return;
      }

      matchMedia.add("(prefers-reduced-motion: reduce)", () => {
        gsap.set(shell, { clearProps: "opacity,visibility,transform" });
      });

      matchMedia.add("(prefers-reduced-motion: no-preference)", () => {
        gsap.fromTo(
          shell,
          { autoAlpha: 0, y: motion.distanceSm },
          {
            autoAlpha: 1,
            y: 0,
            duration: motion.durationBase,
            ease: "power2.out",
            clearProps: "opacity,visibility,transform",
          },
        );
      });

      return () => matchMedia.revert();
    },
    { dependencies: [location, tokenState.state], scope: routeShellRef, revertOnUpdate: true },
  );

  let content: ReactNode;

  switch (tokenState.state) {
    case "LOGGED_IN": {
      const role = getRoleFromToken(tokenState.accessToken);

      if (role === "ADMIN") {
        content = (
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
            <Route path="/users/search">
              <AdminUserSearchPage />
            </Route>
            <Route path="/users/:id">
              {(params) => <AdminUserDetailPage userId={Number(params.id)} />}
            </Route>
            <Route path="/bank-accounts">
              <BankAccountsPage />
            </Route>
            <Route path="/schools">
              <SchoolsPage />
            </Route>
            <Route>
              <Redirect href="/" />
            </Route>
          </Switch>
        );
        break;
      }

      if (role === "USER") {
        content = (
          <Switch>
            <Route path="/">
              <UserDashboardPage />
            </Route>
            <Route path="/mis-hijos">
              <StudentsPage />
            </Route>
            <Route>
              <Redirect href="/" />
            </Route>
          </Switch>
        );
        break;
      }

      content = (
        <Switch>
          <Route path="/">
            <UserDashboardPage />
          </Route>
          <Route path="/mis-hijos">
            <StudentsPage />
          </Route>
          <Route>
            <Redirect href="/" />
          </Route>
        </Switch>
      );
      break;
    }

    case "LOGGED_OUT":
      content = (
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
          <Route path="/forgot-password">
            <ForgotPasswordPage />
          </Route>
          <Route path="/reset-password">
            <ResetPasswordPage />
          </Route>
          <Route>
            <Redirect href="/" />
          </Route>
        </Switch>
      );
      break;

    default:
      content = tokenState satisfies never;
      break;
  }

  return (
    <div key={`${tokenState.state}-${location}`} ref={routeShellRef}>
      {content}
    </div>
  );
}
