# Frontend Agent Guide

Reglas para trabajar en el frontend de **Gestión de Pagos de Viajes**.

## Stack

- React + Vite
- TypeScript estricto
- TanStack Query
- Zod
- CSS Modules
- Vitest / React Testing Library / MSW
- GSAP con `@gsap/react`

No agregar frameworks UI, Tailwind o dependencias nuevas salvo necesidad concreta.

## Arquitectura

Código de negocio por feature:

`src/features/<dominio>/{pages,components,services,types}`

- `pages`: composición y orquestación.
- `components`: UI y lógica cohesiva del dominio.
- `services`: API + hooks de TanStack Query.
- `types`: DTOs, schemas Zod y helpers puros.
- `src/components`: sólo componentes realmente genéricos.

Las pages no deberían convertirse en mega-componentes. Extraer secciones con responsabilidad propia, pero evitar abstracciones prematuras.

## Datos y estado

- Server state → TanStack Query.
- Estado local → `useState`.
- No duplicar estado derivable.
- No usar `useEffect` para sincronizar valores que pueden calcularse directamente.
- Validar respuestas externas con los schemas Zod existentes.
- Reutilizar helpers antes de crear otros nuevos.

## Pagos

Nunca usar floating point para lógica monetaria.

Usar las abstracciones existentes:

- `DecimalString`
- centavos enteros / `BigInt`
- normalizadores y comparadores monetarios existentes

`Number` sólo puede usarse para presentación si no participa en una decisión financiera.

El backend es autoridad para saldo, FX, allocations y reglas financieras.

## Errores

Nunca mostrar al usuario errores técnicos crudos.

No deben aparecer en UI cosas como:

`FIN-001`, `IllegalStateException`, `reportedAmount`, `maxAllowedAmount`, SQL, Hibernate, etc.

Usar la infraestructura central de `ApiError` y mensajes claros en español. No agregar traducciones ad-hoc dentro de cada componente.

## UI / UX

Mantener la estética existente del proyecto.

Antes de diseñar:
1. revisar componentes similares;
2. revisar CSS/tokens existentes;
3. reutilizar patrones antes de inventar otros.

Priorizar jerarquía y claridad sobre cantidad de información.

Evitar:
- información repetida;
- textos explicativos innecesarios;
- cards dentro de cards;
- estilos inline evitables;
- colores semánticos usados como decoración.

Usar progressive disclosure cuando una pantalla administrativa tenga mucha información.

Todo control interactivo debe contemplar `hover`, `focus`, `active`, `disabled` y accesibilidad por teclado.

Revisar siempre desktop y mobile.

## React

Preferir componentes pequeños y cohesivos, no componentes genéricos artificiales.

La lógica que no depende de React debería ser una función pura y testeable.

No introducir Context/global state si TanStack Query o estado local resuelven el problema.

## Animaciones

Para animaciones no triviales leer primero:

`frontend/.github/gsap-react/SKILL.md` (ruta relativa a la raíz del repo; este archivo vive en `frontend/`)

Usar `useGSAP()` y `contextSafe()` según esa skill.

Para hover, color, opacity o transiciones simples preferir CSS.

## Skills de frontend y diseño

Usarlas sólo cuando la tarea realmente corresponda; no cargar todas por defecto.

- `redesign-existing-projects` — rediseños sobre UI existente.
- `design-taste-frontend` — criterio visual y anti-slop.
- `high-end-visual-design` — polish visual importante.
- `web-design-guidelines` — revisión de buenas prácticas web.
- `ui-ux-pro-max` — decisiones de UX/UI y usabilidad.
- `vercel-react-best-practices` — calidad y performance React.
- `vercel-composition-patterns` — composición/refactor de componentes.
- `extract-design-system` — cuando haya que identificar o consolidar patrones visuales existentes.

No usar una skill sólo porque existe: elegir la mínima necesaria para la tarea.

## Alcance

Mantener los diffs enfocados.

No hacer refactors grandes, reorganizaciones o cambios de arquitectura como efecto secundario de una tarea chica.

Si aparece deuda técnica no relacionada, reportarla en vez de arreglarla automáticamente.

## Antes de terminar

Ejecutar según corresponda:

`npm test`
`npm run lint`
`npm run build`
`npx tsc --noEmit`
`git diff --check`

Para cambios visuales, además inspeccionar la UI real en desktop y mobile.

## Principio general

Preferir código explícito, tipado y fácil de modificar dentro de seis meses antes que abstracciones inteligentes o sobreingeniería.