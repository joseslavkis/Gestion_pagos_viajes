# Agent Instructions: Gestión de Pagos de Viajes (Frontend)

Este documento contiene las reglas base y el contexto arquitectónico para cualquier agente o modelo de IA que asista en el desarrollo del frontend de la aplicación "Gestión de Pagos de Viajes".

## 1. Contexto del Negocio
La aplicación es un gestor integral de cuotas (installments), viajes (trips) y alumnos (students). Permite vincular padres/tutores a alumnos, asociar a estos a diferentes viajes configurables con cuotas mensuales, recargos y notificaciones, y a su vez, permitir la carga o aviso de pagos (payment receipts) y su flujo de validación.

## 2. Stack Tecnológico de Frontend
- **Framework:** React + Vite
- **Lenguaje:** TypeScript (Estricto, fuertemente tipado)
- **Estilos:** CSS puro / CSS Modules (`*.module.css`) - No usar TailwindCSS u otros utility-first frameworks salvo que se requiera excepcionalmente.
- **Peticiones:** TanStack Query (React Query)
- **Manejo de estados globales / Caching:** A través de React Query principalmente
- **Validación / Schemas:** Zod
- **Animaciones:** GSAP (Revisar la Skill guardada para sus convenciones)

## 3. Skills Específicas
- **Animaciones con GSAP:** Está configurada la Skill `gsap-react` ubicada en `.github/gsap-react/SKILL.md`. Al realizar animaciones con React, SIEMPRE usar `@gsap/react`, preferir el hook `useGSAP()` por sobre `useEffect()`, utilizar `contextSafe()` para callbacks, evitar side-effects fuera del scope de GSAP y manejar ref/cleanup. (Ver Skill para más detalles).

## 4. Arquitectura y Código
1. **Estructura por Dominios (Features):** Todos los componentes y lógicas se organizan en `src/features/[dominio]` (ej. `trips`, `auth`, `users`, `payments`, `schools`).
   - Dentro de cada feature, subdividir en `/pages`, `/components`, `/services`, `/types`.
2. **Data-Fetching Cohesivo:** Las peticiones al servidor se configuran siempre en la subcarpeta `services/` (ej: `trips-service.ts`) la cual expone los *custom hooks* (ej: `useTrips`, `useAssignUsersBulk`) wrappeando queries / mutations de TanStack Query.
3. **Formularios con Zod:** Para formularios, usamos un ecosistema unificado basado en los tipos DTOs y validadores de Zod (`*DTOSchema`). 
4. **Lógica de UI Reutilizable:** Todos los componentes compartidos genéricos (Layouts, modales base, tooltips, toasts) deberían ir en `src/components`, a diferencia de los de negocio que residen en `features`.

## 5. Criterios de UX / UI
- Mantener siempre consistencia con el diseño visual establecido (tokens CSS de la aplicación, variables `var(--primary)`, etc.).
- Fomentar la usabilidad a través de Modales (`ModalShell`) y micro-interacciones sutiles pero elegantes (utilizando **GSAP** como primera opción si requiere customización temporal/espacial o frameright animation).
- Todo componente interactivo (botones, inputs, links) debe considerar los estados `disabled`, `hover`, y `active`.

## 6. Testing (Opcional pero valorado)
- Preferir test con react-testing-library (`*.test.tsx`) o integration tests si corresponde interactuar con componentes ruteados.

---

> **Nota para el LLM:** Antes de proponer refactors estructurales gigantes, siempre adhierete a este esquema de archivos ya planteado. Si creas nuevos módulos, mantenlos encapsulados en su feature correspondiente.