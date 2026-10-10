# Cuándo se puede registrar un pago (renovación de ciclo)

Regla de negocio ya vigente, ahora EXPUESTA al frontend. El servidor la decide en `CycleRules.openCycle` (al llegar el pago) y la misma regla produce las banderas del perfil (`CycleRules.paymentWindow`); una prueba (`CycleWindowsTest`) las cruza para todos los estados y todas las fechas alrededor de los límites.

## «Clases agotadas»
Clases usadas >= clases incluidas = ciclo `COMPLETED` (se persiste en la misma transacción que descuenta la última clase, aunque falte para la fecha límite). Una clase cuenta como USADA cuando se MARCA (asistió, no vino o escaneo del QR); no cuando se agenda ni cuando ya empezó y sigue sin marcar.

## Cuándo se permite el pago
1. No hay ciclo activo: alumno sin pagos, ciclo `EXPIRED` o ciclo `COMPLETED` (agotado, antes o después de su fecha límite).
2. El ciclo sigue activo pero HOY ya es su última fecha (`end_date`) y no quedan clases ya empezadas sin marcar (si quedan: `409 PENDING_SESSIONS_TO_MARK`).

Con ciclo activo y fecha límite todavía por llegar: `409 ACTIVE_CYCLE_EXISTS`. Texto del botón deshabilitado: «Podrás renovar desde el {fecha} o cuando use sus clases».

## Qué pasa con el ciclo anterior
- `COMPLETED`: queda tal cual (fechas y contadores intactos). Al estar completo no tiene clases agendadas (`usadas + agendadas <= incluidas`), así que no hay traslados ni conflicto de modalidad.
- Activo en su última fecha: se cierra como `EXPIRED` (las sobrantes se pierden) y las clases futuras ya agendadas pasan al ciclo nuevo (ver «Renovación el día de end_date» en CLAUDE.md).
- El ciclo nuevo empieza en la fecha del pago y termina un mes después (días 29/30/31 se ajustan).

## Fecha del pago (`paidOnMin` / `paidOnMax` del perfil)
`paidOnMax` = hoy (America/Bogota). `paidOnMin` = el mayor entre hoy menos 3 días y el fin del ciclo anterior (su `end_date`, o el día en que se completó si fue `COMPLETED`). Con el pago bloqueado ambas son nulas.

## Extensión (`extendFrom` / `extendUntil` del perfil)
Se puede extender el ÚLTIMO ciclo si está activo, o reabrirlo si venció y es el último y no cerró `COMPLETED`. Desde el día siguiente a su fecha límite (hoy, como mínimo, al reabrir) hasta la fecha límite ORIGINAL más `max_extension_days` del entrenador. Si ya no queda margen, `canExtendCycle` es falso.

## Tablero (`GET /api/coach/students/board`)
Un chip por alumno, precedencia: `SUSPENDIDO` > `SIN_ACTIVAR` > `SIN_PLAN` > `POR_VENCER` > `AL_DIA`. `POR_VENCER` lleva `expiringBy`:
- `DAYS`: quedan pocos días (`expiring_soon_days`, 5 por defecto, inclusive). Texto: «Vence en N días» (0: «Vence hoy»).
- `CLASSES`: quedan pocas clases (`expiring_soon_classes`, 1 por defecto) o NINGUNA (ciclo `COMPLETED`). Texto: «Le queda 1 clase» / «Le quedan N clases» / «Sin clases» (0).
- `BOTH`: las dos cosas. Texto: «Vence en N días» (la más urgente; las clases ya salen en el subtítulo).
`SIN_PLAN` = nunca pagó o el último ciclo venció. Los filtros del cliente usan `expiringSoon`, `noPlan` y `minor` junto con `active` (los conteos son de alumnos activos; un suspendido se lista al final y no cuenta en ningún filtro).
