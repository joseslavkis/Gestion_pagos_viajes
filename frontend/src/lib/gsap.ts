import { useGSAP } from "@gsap/react";
import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";

export type MotionProfile = {
  isCompact: boolean;
  distanceSm: number;
  distanceMd: number;
  durationFast: number;
  durationBase: number;
  durationSlow: number;
  staggerFast: number;
  staggerBase: number;
};

let gsapInitialized = false;

export function initGsap() {
  if (gsapInitialized || typeof window === "undefined") {
    return;
  }

  gsap.registerPlugin(useGSAP, ScrollTrigger);
  gsapInitialized = true;
}

export function createGsapMatchMedia() {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
    return null;
  }
  return gsap.matchMedia();
}

export function getMotionProfile(): MotionProfile {
  const isCompactViewport =
    typeof window !== "undefined" &&
    typeof window.matchMedia === "function" &&
    window.matchMedia("(max-width: 768px)").matches;

  if (isCompactViewport) {
    return {
      isCompact: true,
      distanceSm: 8,
      distanceMd: 14,
      durationFast: 0.18,
      durationBase: 0.24,
      durationSlow: 0.34,
      staggerFast: 0.02,
      staggerBase: 0.045,
    };
  }

  return {
    isCompact: false,
    distanceSm: 12,
    distanceMd: 22,
    durationFast: 0.24,
    durationBase: 0.32,
    durationSlow: 0.46,
    staggerFast: 0.03,
    staggerBase: 0.065,
  };
}

export { gsap, ScrollTrigger, useGSAP };
