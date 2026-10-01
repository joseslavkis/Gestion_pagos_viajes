import { useId, useRef } from "react";

import { createGsapMatchMedia, getMotionProfile, gsap, useGSAP } from "@/lib/gsap";

import styles from "./AnimatedProyectoVaLogo.module.css";
import {
  BRAND_BLUE,
  BRAND_INK,
  LOGO_LABEL,
  LOGO_VIEW_BOX,
  PROYECTO_PATH,
  TAGLINE_PATH,
  TWENTY_FIVE_PATHS,
  TWENTY_FIVE_STROKE_WIDTH,
  VA_PATH,
} from "./logoPaths";

/** The "25" runs on a horizontal gradient between the two brand colours. */
const GRADIENT_FROM = 36;
const GRADIENT_TO = 124;

const STROKE_SELECTOR = 'g[data-logo-part="25"] path';

export function AnimatedProyectoVaLogo() {
  const rootRef = useRef<HTMLDivElement | null>(null);
  // useId() emits colons, which are legal but awkward inside a url(#...) ref.
  const gradientId = useId().replace(/:/g, "");

  useGSAP(
    () => {
      const root = rootRef.current;
      if (!root) {
        return;
      }

      const mm = createGsapMatchMedia();
      // Without matchMedia we simply leave the markup in its authored final
      // state: fully drawn "25", all wordmark parts visible.
      if (!mm) {
        return;
      }

      // Everything is painted by default, so a skipped timeline must never leave
      // an element invisible. The reduced-motion branch does nothing at all.
      mm.add("(prefers-reduced-motion: no-preference)", () => {
        const motion = getMotionProfile();
        const speed = motion.isCompact ? 0.82 : 1;
        const shift = motion.isCompact ? 0.75 : 1;

        const proyecto = root.querySelector<SVGGElement>('g[data-logo-part="proyecto"]');
        const va = root.querySelector<SVGGElement>('g[data-logo-part="va"]');
        const tagline = root.querySelector<SVGGElement>('g[data-logo-part="tagline"]');
        const wordmarks = [proyecto, va, tagline].filter((el): el is SVGGElement => el != null);

        const drawable = Array.from(
          root.querySelectorAll<SVGPathElement>(STROKE_SELECTOR),
        ).filter((path) => typeof path.getTotalLength === "function");

        const drawDuration = 0.82 * speed;

        const timeline = gsap.timeline({
          // Hand the resting logo back as plain authored SVG: no leftover inline
          // dash styles once the whole sequence has finished. This must run once
          // for the whole timeline, not per tween, or a finishing stroke would
          // snap the still-animating ones to fully drawn.
          onComplete: () => {
            gsap.set(drawable, { clearProps: "strokeDasharray,strokeDashoffset" });
          },
        });
        gsap.set(wordmarks, { autoAlpha: 0 });

        if (drawable.length > 0) {
          drawable.forEach((path) => {
            const length = path.getTotalLength();
            gsap.set(path, { strokeDasharray: length, strokeDashoffset: length });
          });

          drawable.forEach((path, index) => {
            timeline.to(
              path,
              {
                strokeDashoffset: 0,
                duration: drawDuration,
                ease: "power2.inOut",
              },
              index * drawDuration * 0.28,
            );
          });
        }

        timeline
          .fromTo(
            proyecto,
            { autoAlpha: 0, x: -8 * shift },
            { autoAlpha: 1, x: 0, duration: 0.3 * speed, ease: "power2.out" },
            drawDuration * 0.92,
          )
          .fromTo(
            va,
            { autoAlpha: 0, scale: 0.92, transformOrigin: "50% 50%" },
            { autoAlpha: 1, scale: 1, duration: 0.42 * speed, ease: "back.out(1.5)" },
            drawDuration * 1.14,
          )
          .fromTo(
            tagline,
            { autoAlpha: 0, y: 5 * shift },
            { autoAlpha: 1, y: 0, duration: 0.3 * speed, ease: "power2.out" },
            drawDuration * 1.34,
          );
      });

      return () => mm.revert();
    },
    { scope: rootRef },
  );

  return (
    <div ref={rootRef} className={styles.root}>
      <svg
        className={styles.svg}
        viewBox={LOGO_VIEW_BOX}
        role="img"
        aria-label={LOGO_LABEL}
        xmlns="http://www.w3.org/2000/svg"
      >
        <defs>
          <linearGradient
            id={gradientId}
            x1={GRADIENT_FROM}
            y1={0}
            x2={GRADIENT_TO}
            y2={0}
            gradientUnits="userSpaceOnUse"
          >
            <stop offset="0" stopColor={BRAND_INK} />
            <stop offset="1" stopColor={BRAND_BLUE} />
          </linearGradient>
        </defs>

        <g
          data-logo-part="25"
          fill="none"
          stroke={`url(#${gradientId})`}
          strokeWidth={TWENTY_FIVE_STROKE_WIDTH}
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          {TWENTY_FIVE_PATHS.map((d) => (
            <path key={d} d={d} />
          ))}
        </g>

        <g data-logo-part="proyecto">
          <path d={PROYECTO_PATH} fill={BRAND_INK} fillRule="nonzero" />
        </g>
        <g data-logo-part="va">
          <path d={VA_PATH} fill={BRAND_BLUE} fillRule="nonzero" />
        </g>
        <g data-logo-part="tagline">
          <path d={TAGLINE_PATH} fill={BRAND_BLUE} fillRule="nonzero" />
        </g>
      </svg>
    </div>
  );
}
