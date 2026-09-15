# Retiro de columnas obsoletas de multas

Este cambio separa el despliegue del código del retiro físico de las columnas. El backend deja de mapearlas primero; el `DROP` se ejecuta únicamente después de verificar el comportamiento en producción.

## Shim transitorio de compatibilidad (no persistente)

Durante la ventana de rollout coexisten viejas y nuevas versiones de backend y frontend. Para evitar acoplar el despliegue a una activación coordinada, esta PR introduce un shim de transporte **no persistente** que solo afecta el wire format, no la lógica de negocio ni la base de datos:

- **Backend (request):** `TripCreateDTO` y `TripUpdateDTO` aceptan `fixedFineAmount` pero lo ignoran vía `@JsonIgnoreProperties(value = {"fixedFineAmount"})`; no se usa `ignoreUnknown=true` ni se amplía la whitelist a otros campos.
- **Backend (response):** `TripDetailDTO` expone `fixedFineAmount` mediante `legacyFixedFineAmount()` retornando `BigDecimal.ZERO`, y `SpreadsheetRowInstallmentDTO` expone `fineAmount` mediante `legacyFineAmount()` retornando `BigDecimal.ZERO`. Los componentes del record se mantienen sin cambios: `Trip` no tiene `fixedFineAmount`, `Installment` no tiene `fineAmount`, e `Installment.totalDue` sigue siendo el `capitalAmount`.
- **Frontend (request):** solo en `useCreateTrip` se añade `fixedFineAmount: 0` al payload en el límite HTTP. No se agrega al esquema de Zod, al formulario, a los defaults, a la UI ni al `PATCH`.
- **Frontend (parse):** los esquemas Zod de respuesta (`TripDetailDTOSchema`, `SpreadsheetDTOSchema`) ya filtran claves desconocidas por defecto, así que una respuesta que incluya `fixedFineAmount` o `fineAmount` parsea sin error y no expone esas claves al dominio.

Las cuatro combinaciones viejo/nuevo de FE/BE siguen siendo compatibles **a nivel de transporte/API** durante la ventana de rollout: no se rompe el parseo ni se devuelve 400 en el create por el campo retirado. Esta compatibilidad es estrictamente de wire format — el shim no garantiza la invariante de importes contra builds viejos del backend que ya tenían lógica de multas activa.

Concretamente: cuando un frontend nuevo se comunica con un backend viejo, puede seguir recibiendo respuestas con `totalDue` afectado por la lógica de multas del backend viejo. El frontend nuevo no expone un campo de multa separado (no lo dibuja en UI, no lo muestra al usuario). La invariante `totalDue == capitalAmount` la garantiza el backend nuevo una vez desplegado, y se ratifica por la consulta de pre-despliegue documentada más abajo — no la garantiza retroactivamente el shim contra builds anteriores. Por eso el orden de despliegue importa para la **correctitud financiera** aunque el shim haga que ambas puntas levanten sin errores.

## Fases

1. **Fase 1: desplegar el código**

   Desplegar la versión que ya no usa multas ni persiste `fixedFineAmount` o `fineAmount`, pero sí incluye el shim de compatibilidad descrito arriba.

2. **Fase 2: smoke test de producción**

   Verificar como mínimo:

   - crear un viaje (con y sin `fixedFineAmount` en el body);
   - asignar un alumno;
   - abrir el spreadsheet;
   - registrar un pago;
   - revisar y aprobar el pago;
   - verificar cuotas vencidas y que su total sea el capital (`total_due == capital_amount`) tanto en la API como en la base;
   - verificar un caso retroactivo y que conserve el estado `RETROACTIVE`;
   - confirmar que `fixedFineAmount == 0` en `GET /api/v1/trips/{id}` y que `fineAmount == 0` en cada fila del spreadsheet.

3. **Fase 3: cerrar la ventana de rollback**

   Esperar una ventana razonable sin incidentes antes de retirar columnas.

4. **Fase 4: backup**

   Crear y verificar un backup de la base de datos.

5. **Fase 5: ejecutar el retiro**

   Antes de ejecutar el `DROP`, correr **manualmente** la siguiente consulta de pre-despliegue (sólo lectura, sin `UPDATE` automático):

   ```sql
   SELECT COUNT(*) AS cuotas_con_total_distinto
   FROM installments
   WHERE total_due IS DISTINCT FROM capital_amount;
   ```

   El resultado esperado es `0`. Si es mayor a `0`, **no desplegar**: hay cuotas donde `total_due` no coincide con `capital_amount`, lo que implica que persisten efectos de multas que aún no fueron reseteados por código. No se ejecuta ningún `UPDATE` automático desde este pipeline: la limpieza de importes financieros requiere una decisión manual.

   Una vez confirmado que la consulta anterior devuelve `0`, ejecutar manualmente, con `ON_ERROR_STOP=1`:

   ```sh
   psql ... -v ON_ERROR_STOP=1 \
     -f backend/sql/20260915_drop_dead_fine_columns.sql
   ```

   El script bloquea ambas tablas, verifica que no haya valores distintos de cero en las columnas obsoletas y que cada `total_due` coincida con `capital_amount`. Si encuentra una inconsistencia, falla antes de borrar cualquier columna y no modifica importes financieros.

6. **Fase 6: verificar el schema**

   ```sql
   SELECT table_name, column_name
   FROM information_schema.columns
   WHERE
       (table_name = 'trips'
        AND column_name = 'fixed_fine_amount')
    OR (table_name = 'installments'
        AND column_name = 'fine_amount');
   ```

   El resultado esperado después del `DROP` es `0 rows`.

## Compatibilidad de despliegue

El backend se despliega en el VPS mediante GitHub Actions y el frontend en Vercel. El shim transitorio descrito arriba elimina la necesidad de coordinar la activación del backend y del frontend: durante toda la ventana de rollout, las cuatro combinaciones de FE viejo/nuevo contra BE viejo/nuevo siguen funcionando. Si la infraestructura permite una activación atómica, el shim sigue siendo benigno (siempre devuelve `0`/ignora el valor) y se retira en la tarea de seguimiento.

Después de hacer `COMMIT` del `DROP`, una rollback directa a una versión antigua que todavía mapea estas columnas ya no es segura sin restaurar primero el schema.

## Tarea futura: `Remove legacy fine transport shim`

Una vez cerrada la ventana de rollout y confirmado que no quedan clientes viejos en producción, retirar el shim. Concretamente:

- Eliminar `@JsonIgnoreProperties(value = {"fixedFineAmount"})` en `TripCreateDTO` y `TripUpdateDTO` (backend).
- Eliminar los métodos `legacyFixedFineAmount()` y `legacyFineAmount()` que retornan `BigDecimal.ZERO` en `TripDetailDTO` y `SpreadsheetRowInstallmentDTO` (backend).
- Eliminar la inyección `fixedFineAmount: 0` en `useCreateTrip` (frontend), y los asserts de tests asociados al shim.
- Mantener los tests de compatibilidad (legacy keys se siguen parseando / aceptando sin error) durante la ventana de deprecación, y borrarlos junto con el shim.

Esta tarea se tracker separadamente para evitar que la lógica del shim quede accidentalmente en `main` tras el rollout.
