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
Filtros (conteos del servidor, `counts = {all, expiring, active, inactive}`; `all = active + inactive`):
- **Activos** (`activeCycle`): alumno no suspendido cuyo ÚLTIMO ciclo está `ACTIVE`.
- **Por vencer** (`expiringSoon`): subconjunto de Activos con pocos días (`expiring_soon_days`, 5 por defecto, inclusive) o pocas clases (`expiring_soon_classes`, 1 por defecto, inclusive). Un ciclo agotado (`COMPLETED`) NO es activo, así que no entra aquí.
- **Inactivos** (`!activeCycle`): nunca pagó, ciclo vencido, ciclo agotado o alumno suspendido.
- **Todos**: todos los alumnos listados, suspendidos incluidos.

Chip de la fila (uno por alumno; precedencia): `SUSPENDIDO` > `SIN_ACTIVAR` (invitación sin aceptar) > por el último ciclo: `SIN_CLASES` («Sin clases · renovar», ciclo `COMPLETED`), `VENCIDO` («Vencido · renovar», ciclo `EXPIRED`), `SIN_PLAN` (nunca pagó), `POR_VENCER`, `AL_DIA`. Las banderas no dependen del chip (un `SIN_ACTIVAR` con ciclo activo sigue contando en Activos).

`POR_VENCER` lleva `expiringBy`:
- `DAYS`: pocos días. Texto: «Vence en N días» (0: «Vence hoy»).
- `CLASSES`: pocas clases. Texto: «Le queda 1 clase» / «Le quedan N clases».
- `BOTH`: las dos. Texto: «Vence en N días» (la más urgente; las clases salen en el subtítulo).

`needsRenewal` (no suspendido, último ciclo `COMPLETED` o `EXPIRED`) marca «renovar». Orden de las filas: primero los activos por nombre; luego los inactivos: los que hay que renovar, después el resto (sin plan, sin activar sin ciclo), y al final los suspendidos, cada grupo por nombre. Una fila lleva siempre los datos de su ÚLTIMO ciclo (modalidad, clases, fecha límite) aunque no esté activo; solo `daysUntilEnd` es exclusivo del ciclo activo.

## Un ciclo vigente no cambia de plan ni de modalidad
No existe ningún camino que cambie el plan, la modalidad ni las clases incluidas de un ciclo: el ciclo guarda una COPIA al abrirse (`plan_id`, `modality`, `classes_included` son `updatable = false` y solo el constructor los fija); el pago solo abre ciclos nuevos; editar o desactivar un plan solo afecta a los pagos futuros. La modalidad solo puede cambiar al renovar, es decir, cuando el ciclo terminó por fecha o por clases (o el mismo día de `end_date`, con el conflicto de modalidad explicado en `docs/error-codes.md`). Probado en `BoardAndProfileTest` (se edita todo el plan y se desactiva: el ciclo conserva modalidad y clases, y el alumno sigue agendando y marcando). El nombre del plan que muestra el perfil es el nombre ACTUAL del plan (solo una etiqueta).
