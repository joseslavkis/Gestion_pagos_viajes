# Propuesta Funcional de la App 
## Gestión de Pagos y Validación de Comprobantes para Viajes Grupales

**Fecha:** 24 de marzo de 2026

---

## Alcance del sistema
La app está orientada a la gestión de pagos y validación de comprobantes de viajes grupales administrados por una agencia.
Los administradores son el dueño de la agencia y/o empleados autorizados.
Los usuarios son padres/tutores que pagan el viaje de sus hijos.

## ¿Qué es la app?
Es una plataforma web para administrar pagos de viajes grupales.
La app no cobra dinero directamente: funciona como un registro digital donde los padres/tutores informan pagos y suben comprobantes, y la administración de la agencia valida esos comprobantes.

### Incluye
* Panel de usuarios (padres/tutores) con vista de todos sus viajes, cuotas y estados de pago.
* Panel de administración (dueño/empleados de la agencia).
* **Plantilla interna tipo Excel integrada en la app** (sin depender de Excel externo para operar).
* Descarga de plantilla en archivo Excel (`.xlsx`).
* Gestión segura de comprobantes (JPG/PDF).
* Gestión de multas fijas por mora condicional y ajuste automático por vencimiento.

## Aclaración clave sobre la plantilla interna
La plantilla interna es una implementación propia de la app con comportamiento tipo Excel (grilla tabular editable, filtros y ordenamiento), integrada directamente en el sistema.
**No es un archivo Excel externo embebido ni una dependencia de Google Sheets/Microsoft Excel para trabajar día a día.**
La operación diaria se realiza dentro de la app y, adicionalmente, se puede exportar la información a `.xlsx` cuando se necesite compartir o archivar.

## ¿Qué resuelve?
* Centraliza la información de cuotas de cada viaje.
* Permite saber qué está pago, pendiente o vencido.
* Ordena la validación de comprobantes.
* Facilita el seguimiento administrativo con una plantilla clara y exportable.
* Aplica multas por atrasos prolongados de forma automática y auditable.

## Roles del sistema

### Administrador (Agencia)
* Crear, editar y gestionar viajes.
* Definir por viaje:
  * monto total,
  * cantidad de cuotas,
  * día de vencimiento,
  * días previos para estado "amarillo",
  * monto fijo de multa por mora (valor monetario),
  * **aplicar retroactividad a nuevos inscriptos (sí/no)**.
* Asignar/desasignar familias (usuarios) a viajes (individual y masivo opcional).
* Ver cuentas de cada usuario.
* Revisar comprobantes y aceptar/rechazar con observación.
* Usar la plantilla interna tipo Excel del viaje.
* Descargar la plantilla en Excel (`.xlsx`).
* Ver y descargar comprobantes con permisos de administrador.
* Ajustar manualmente excepciones de multas con trazabilidad (quién, cuándo y motivo).

### Usuario (Padre/Tutor)
* Al iniciar sesión, ver inmediatamente el estado de pago de **todos sus viajes activos**.
* Por cada viaje, visualizar **cada cuota individualmente** con su estado representado por color:
  * <span style="color:green">**Verde**</span>: cuota pagada y validada por administración, o con mucho tiempo hasta el vencimiento.
  * <span style="color:orange">**Amarillo**</span>: cuota próxima al vencimiento, o con comprobante pendiente de aprobación por el administrador.
  * <span style="color:red">**Rojo**</span>: cuota vencida sin regularizar, o comprobante rechazado por el administrador.
* Ver su estado de cuenta:
  * deuda actual,
  * cuotas pagadas y pendientes,
  * próximos vencimientos,
  * multa aplicada por mora (si corresponde),
  * **total actualizado a pagar** por cuota.
* Cargar comprobantes de pago (JPG/PDF) informando:
  * importe,
  * fecha de pago,
  * método de pago.
* Ver el estado de sus comprobantes (pendiente de revisión, aprobado o rechazado).
* Si un comprobante es rechazado, ver la observación del administrador y poder enviar uno nuevo.

## Semáforo de pagos
Cada cuota se representa visualmente con un color. Los estados internos del sistema se traducen a colores de la siguiente manera:

| **Color mostrado** | **Estado interno** | **Significado** |
| :--- | :--- | :--- |
| <span style="color:green">**Verde**</span> | GREEN | Cuota pagada y validada por admin |
| <span style="color:orange">**Amarillo**</span> | YELLOW | Próxima al vencimiento o pendiente de revisión |
| <span style="color:red">**Rojo**</span> | RED | Cuota vencida sin pagar |
| <span style="color:red">**Rojo**</span> | RETROACTIVE (rechazado) | Comprobante rechazado por admin |

**Regla de prioridad de color:** si una cuota tiene un comprobante rechazado, se muestra en rojo independientemente de su fecha de vencimiento.
Si tiene un comprobante pendiente de aprobación, se muestra en amarillo.
El texto que ve el usuario nunca expone los nombres técnicos internos (GREEN, YELLOW, RED, RETROACTIVE): siempre se muestran etiquetas legibles en español.

## Multa por mora (Atraso mayor a una cuota)
La app contempla la aplicación de una multa de valor fijo para aquellos casos donde el atraso sea significativo.
**La configuración del monto de la multa es obligatoria al crear cada viaje** (pudiendo ser cero si la agencia así lo define).

### Parámetros obligatorios al crear viaje
* **Monto fijo de multa** (valor monetario a aplicar como penalidad).

### Regla de cálculo de la multa
La multa se aplica como un recargo fijo a la cuota adeudada, exclusivamente cuando el usuario acumula más de una cuota en estado de mora (vencida e impaga).
Sea:
* C: capital de la cuota,
* Mf: monto fijo de la multa definida para el viaje,
* cv: cantidad total de cuotas vencidas e impagas del usuario.

Multa aplicable a la cuota (M):
* Si cv > 1, entonces M = Mf
* Si cv <= 1, entonces M = 0

Total exigible:
Total exigible = C + M + Retroactivo (si aplica)

## Actualización de multas por decisión administrativa
La administración puede modificar el valor fijo de la multa de un viaje cuando lo considere necesario.
Ese cambio se aplica a todas las cuotas **pendientes o vencidas que aún no estén pagadas**.
Las cuotas **ya pagadas** no se modifican.

## Notificaciones automáticas
Cuando una cuota pasa a estado amarillo, la app envía un email recordatorio al usuario con:
* viaje,
* cuota/período,
* monto,
* fecha límite de pago,
* aviso de posible multa en caso de acumular atrasos.

## Plantilla interna tipo Excel (una sola por viaje)
Cada viaje tiene una única plantilla administrativa integrada en la app.
La plantilla consolida todos los usuarios del viaje en una sola vista y agrega tantas columnas de cuota como corresponda al plan de pagos definido.

### Estructura
* Columnas de datos del usuario: Nombre, Apellido, Teléfono, Email, Alumno, Colegio, Curso.
* Columnas dinámicas de cuotas: `Cuota 1, Cuota 2, ..., Cuota N`.
* Cada columna de cuota refleja:
  * estado representado por color (verde/amarillo/rojo),
  * capital de cuota,
  * multa por mora aplicada (si corresponde),
  * retroactivo (si aplica),
  * total exigible.

### Funciones de la plantilla
* filtros,
* ordenamiento,
* búsqueda,
* navegación por páginas.

## Regla de retroactividad (Opcional)
Al momento de crear un viaje, la administración puede definir si el cobro retroactivo aplica o no para ese grupo.

### Criterio operativo
* **Si está inactiva:** El usuario que ingresa tarde solo es responsable de las cuotas con fecha de vencimiento posterior a su alta.
* **Si está activa:** Las cuotas anteriores al momento del alta se consideran deuda retroactiva y se suman al importe de la primera cuota pendiente.

## Estructura de cuotas por usuario
Para cada viaje, el sistema genera una cuota por cada período para cada usuario asignado.
Cada usuario tiene su propio conjunto de cuotas dentro de cada viaje.

## Exportación a Excel (`.xlsx`)
Desde administración se puede descargar la plantilla del viaje en formato `.xlsx`, con los datos actualizados y formato de lectura claro para compartir o archivar.

## Seguridad de archivos
* Los comprobantes no quedan públicos en internet.
* Solo usuarios autorizados (administradores de la agencia) pueden acceder.
* El acceso a archivos se controla desde la app con autenticación.

## Reglas operativas importantes
* No se puede marcar un pago como aceptado sin comprobante asociado.
* Si se rechaza un comprobante, se registra motivo/observación y la cuota se muestra en rojo.
* El estado del semáforo se actualiza automáticamente según fecha y validación.
* Cada usuario ve solo sus datos; administración ve el conjunto completo.
* Se puede agregar usuarios al viaje en cualquier momento, aplicando la regla de retroactividad según la configuración del viaje.
* La multa por mora se aplica automáticamente si el sistema detecta que el usuario tiene más de una cuota en estado vencido.
* **Los colores mostrados al usuario nunca exponen nombres técnicos internos**: siempre se usan etiquetas en español claras y comprensibles.

## Resultado esperado
Una app completa para la agencia que permita operar viajes con control de cuotas por usuario, validación de comprobantes, alertas automáticas, seguimiento en plantilla interna tipo Excel integrada y exportación a Excel, con reglas claras de morosidad y trazabilidad administrativa.