# Backend Agent Guide (Spring Boot)

Este archivo define como debe trabajar un agente de codigo dentro de `backend/`.
Prioriza cambios pequenos, seguros y testeables.

## Stack y contexto

- Java 21
- Spring Boot 3.4.x
- Spring Web + Validation + Security + JPA
- PostgreSQL
- JWT (jjwt)
- OpenAPI (springdoc)
- Tests con JUnit + Spring Test + Testcontainers

## Objetivo del agente

Implementar cambios backend sin romper contratos API, seguridad, persistencia ni reglas de negocio de pagos/cuotas.

## Reglas de arquitectura

- Mantener separacion por capas:
  - `controllers`: entrada HTTP, validacion basica y codigos de respuesta.
  - `services`: logica de negocio y orquestacion.
  - `repositories`: acceso a datos.
  - `entities`: modelo persistente.
  - `dtos`: contratos de request/response.
- No mover logica de negocio compleja a controladores.
- Evitar logica SQL en controladores.
- Evitar respuestas con entidades JPA directas; usar DTOs.

## Estructura aproximada de carpetas

Usar esta estructura como referencia para ubicar codigo nuevo:

```text
backend/
  src/
    main/
      java/
        com/agencia/pagos/
          config/                 # seguridad, cors, beans de infraestructura
          controllers/            # endpoints REST
          dtos/                   # request/response DTOs
          entities/               # entidades JPA
          repositories/           # interfaces JpaRepository
          services/               # logica de negocio
          security/               # jwt, filtros, utilidades de auth
          schedulers/             # tareas programadas (si aplica)
          exceptions/             # manejo centralizado de errores
          mappers/                # conversion entity <-> dto (si aplica)
      resources/
        application.properties
        application-local.properties
    test/
      java/
        com/agencia/pagos/
          controllers/            # tests de capa web/controladores
          services/               # tests unitarios/integracion de negocio
          repositories/           # tests de persistencia
      resources/
```

Reglas de ubicacion:

- Endpoint nuevo: `controllers/` + DTOs en `dtos/` + logica en `services/`.
- Query/operacion de BD: `repositories/` (y solo lo necesario en `services/`).
- Regla de autenticacion/autorizacion: `security/` o `config/`.
- Job recurrente: `schedulers/` + soporte en `services/`.
- Error de negocio reutilizable: `exceptions/`.

## Convenciones de implementacion

- Reutilizar patrones existentes del proyecto antes de crear nuevos.
- Mantener nombres explicitos y consistentes con el dominio (trip, installment, payment, reminder).
- No introducir librerias nuevas si Spring o el proyecto ya resuelven el problema.
- No hacer refactors masivos en tareas chicas.

## API y contratos

- No romper contratos de endpoints existentes salvo pedido explicito.
- Si se agrega un campo en response, mantener compatibilidad hacia atras.
- Validar inputs con Bean Validation (`@NotNull`, `@NotBlank`, etc.) en DTOs de entrada.
- Mensajes de error deben ser claros y accionables.

## Seguridad

- Preservar reglas de autorizacion existentes.
- Nunca exponer secretos, tokens, passwords ni datos sensibles en logs.
- Si un endpoint toca pagos/cuotas, validar rol y ownership correctamente.
- No desactivar controles de seguridad para "hacer que funcione".

## Persistencia y transacciones

- Mantener operaciones de negocio criticas dentro de transacciones cuando corresponda.
- Evitar N+1 queries evidentes; usar estrategias de carga adecuadas.
- Si se modifica una entidad, revisar impacto en repositorios y mapeos relacionados.

## Notificaciones y tareas programadas

- Cambios en schedulers/reminders deben ser idempotentes.
- Evitar duplicar envios de recordatorios.
- Registrar logs utiles (sin datos sensibles) para auditoria operativa.

## Testing minimo esperado

Para cambios de backend:

- Si toca controlador: agregar/ajustar test de controlador.
- Si toca servicio con logica: agregar/ajustar test unitario o de integracion.
- Si toca persistencia compleja: cubrir caso con test de integracion.
- Verificar casos borde y validaciones negativas.

## Checklist antes de finalizar

- Compila sin errores.
- Tests relevantes pasan.
- No hay cambios colaterales innecesarios.
- No hay secretos hardcodeados.
- Se mantiene compatibilidad API cuando aplica.
- Se documenta brevemente que se cambio y por que.

## Fuera de alcance (a menos que se pida)

- Migraciones o refactors estructurales amplios.
- Cambios de stack o framework.
- Renombrado masivo de paquetes/clases.
