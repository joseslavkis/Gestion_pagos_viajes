# Retiro de columnas obsoletas de multas

Este cambio separa el despliegue del código del retiro físico de las columnas. El backend deja de mapearlas primero; el `DROP` se ejecuta únicamente después de verificar el comportamiento en producción.

## Fases

1. **Fase 1: desplegar el código**

   Desplegar la versión que ya no usa multas ni expone `fixedFineAmount` o `fineAmount`.

2. **Fase 2: smoke test de producción**

   Verificar como mínimo:

   - crear un viaje;
   - asignar un alumno;
   - abrir el spreadsheet;
   - registrar un pago;
   - revisar y aprobar el pago;
   - verificar cuotas vencidas y que su total sea el capital;
   - verificar un caso retroactivo y que conserve el estado `RETROACTIVE`.

3. **Fase 3: cerrar la ventana de rollback**

   Esperar una ventana razonable sin incidentes antes de retirar columnas.

4. **Fase 4: backup**

   Crear y verificar un backup de la base de datos.

5. **Fase 5: ejecutar el retiro**

   Ejecutar manualmente, con `ON_ERROR_STOP=1`:

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

El backend se despliega en el VPS mediante GitHub Actions y el frontend en Vercel. El contrato final es incompatible con bundles viejos en ambas direcciones:

- un frontend viejo exige `fixedFineAmount` y `fineAmount` al parsear respuestas con Zod;
- un frontend nuevo omite `fixedFineAmount`, que el backend viejo todavía exige al crear viajes.

Por eso este cambio requiere coordinar la activación del backend y del frontend, evitando una ventana abierta con clientes viejos. No se agrega un shim transitorio: mantener el contrato final limpio evita conservar una feature retirada. Si la infraestructura no permite una activación coordinada, hay que diseñar y anunciar un shim separado antes del despliegue.

Después de hacer `COMMIT` del `DROP`, una rollback directa a una versión antigua que todavía mapea estas columnas ya no es segura sin restaurar primero el schema.
