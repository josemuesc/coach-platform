# CLAUDE.md — Plataforma MVP para entrenadores personales

Responde y comenta en español. Código, clases, tablas y variables en inglés.

`docs/prompt-inicial.md` es el documento de referencia original. Si hay contradicciones con este archivo, manda CLAUDE.md.

## Producto
PWA multi-entrenador (multi-tenant) que reemplaza hojas de cálculo y chats: el entrenador gestiona alumnos, agenda, paquetes, pagos y seguimiento; el alumno ve su ciclo, agenda/cancela y ve su progreso. Piloto: entrenador de Smart Fit (Colombia), 10 alumnos. Mercado: Colombia, COP, America/Bogota, español. Prioridad: simplicidad; WhatsApp como canal principal. El alumno ve la marca de SU entrenador.

## Stack
- Backend: Java 21 + Spring Boot 4.1.x (Maven), Spring Security + JWT, Flyway (todo cambio de BD por migración, nunca manual).
- Pruebas: unitarias rápidas con `mvn test`; integración con Testcontainers + PostgreSQL real (`*IT`, base `PostgresIntegrationTest`, `mvn verify`, requiere Docker). H2 solo para pruebas triviales.
- BD: PostgreSQL en Supabase usado SOLO como Postgres (auth y reglas en Spring). Conectar por el **pooler**. RLS activado en todas las tablas sin políticas públicas.
- Frontend: React + Vite + Tailwind, mobile-first, PWA (manifest + service worker).
- Multi-tenant: una BD, `coach_id` en todas las tablas de negocio; filtro de tenant central en el servidor, nunca confiar en el cliente.
- Preparación futura: tabla `organization` (id, name, created_at) y `coach.organization_id` NULLABLE (FK). En el MVP es solo estructura: ningún entrenador tiene organización (null), no hay interfaz ni lógica, y `organization_id` no cambia ninguna regla de acceso; el aislamiento sigue siendo por `coach_id`.
- Fechas: instantes en UTC; fechas de ciclo `LocalDate` en zona del entrenador (America/Bogota).
- Despliegue barato: backend Railway/Render/VPS, frontend estático.

## Reglas de negocio
**Planes:** por entrenador (nombre, nº clases, precio). Ej. piloto: 8 → 520.000; 12 → 620.000; 16 → 720.000 COP.

**Ciclo**
- Nace al registrar un pago; inicia el día del pago y vence `fechaPago.plusMonths(1)`.
- Pago tardío: el ciclo nuevo empieza el día que paga.
- Cierra por lo primero que ocurra: clases agotadas → `completado`; llega la fecha límite con clases sin usar → `vencido` (las sobrantes se pierden, sin arrastre).
- Un solo ciclo activo por alumno. Pago nuevo con ciclo activo: bloquear (o advertir claramente). Sin ciclo activo no se agenda.

**Clases** — estados: `scheduled`, `attended`, `cancelled_on_time`, `rescheduled`, `no_show`, `cancelled_by_coach`.
- Una clase "vista" descuenta 1 del ciclo: asistencia marcada, cancelación tardía o no-show.
- Ventana de cancelación: 2 h (configurable en `coach_settings.cancel_window_hours`). ≥ 2 h antes: no descuenta, se reagenda dentro del mismo ciclo (nueva fecha ≤ vencimiento del ciclo). < 2 h: no se puede cancelar y queda como vista.
- Validación de la ventana SIEMPRE en el servidor con la hora del servidor.
- Cancela el profe: no descuenta; se reagenda en el ciclo. Si el ciclo vence antes, el profe puede extender la fecha límite (registrar quién y cuándo).

## Roles
- COACH: marca, ajustes, planes, alumnos (invitación por enlace), disponibilidad, agenda, asistencia, cancelar/reagendar, extender ciclo, pagos manuales (Nequi/transferencia/efectivo), medidas y notas.
- STUDENT: ver marca, ciclo (restantes y fecha límite), agendar según disponibilidad, cancelar/reagendar (regla 2 h), historial, medidas y gráfica.

## Fuera de alcance (NO construir)
Pagos en línea, push nativo, rutinas y fotos de progreso, nutrición, multi-entrenador por gimnasio, IA, app nativa, cobro de suscripción a entrenadores (dejar solo la estructura lista para un campo de plan/suscripción del coach), módulo para organizaciones o gimnasios (reportes de cumplimiento de sus entrenadores).

## Futuro comercial (NO implementar)
A futuro una organización podría ver únicamente métricas de cumplimiento de sus entrenadores (clases dadas vs. agendadas, cancelaciones del profe), nunca pagos, precios, medidas ni datos personales de los alumnos, y solo con aceptación explícita del entrenador.

## Notificaciones (fase 5)
`@Scheduled` (+ShedLock si hay varias instancias) crea filas en `notification`; un sender las envía con reintentos. Interfaz de proveedor de mensajería con implementación WhatsApp Cloud API y otra mock (log). Plantillas de Meta documentadas en un archivo. Sin opt-in registrado no se envía nada. Medidas = datos de salud → autorización Ley 1581. Respaldo: botón `wa.me`.

## Seguridad y calidad
- Tenant en TODAS las consultas, vía filtro central. Ninguna consulta ni filtro usa `organization_id` para conceder acceso.
- Validaciones de negocio en servidor; el frontend solo refleja.
- Hash seguro de contraseñas, JWT con expiración, invitaciones de un solo uso.
- Pruebas unitarias obligatorias del servicio de ciclos ANTES de construir encima: vencimiento (pagos días 29/30/31), completado vs vencido, un solo ciclo activo, bloqueo de pago con ciclo activo, ventana de 2 h, reagendar solo dentro del ciclo, extensión por el profe.

## Decisiones confirmadas
- Invitación de alumno: enlace con token de un solo uso y con expiración; el alumno define su propia contraseña. NO se generan contraseñas aleatorias (esto reemplaza la decisión anterior de `must_change_password` por contraseña aleatoria).
- "Un solo ciclo activo por alumno": índice único parcial en la base (`UNIQUE (student_id) WHERE status = 'ACTIVE'`), bloqueo de la fila del alumno (`SELECT ... FOR UPDATE`) y validación en el servicio. Probado con concurrencia contra Postgres real.
- `cycle.end_date` es el ÚLTIMO día utilizable (inclusive). El ciclo pasa a `EXPIRED` a partir del día siguiente; si se agotan las clases es `COMPLETED` (gana sobre `EXPIRED`).
- Renovación: con ciclo activo, el pago solo se permite desde el día de `end_date` en adelante; cierra el ciclo viejo como `EXPIRED` (sobrantes perdidas) y abre el nuevo. Antes de `end_date`, bloqueado. Sin ciclo activo (completado/vencido) se puede pagar en cualquier momento.
- Fecha de pago (`paidOn`): opcional, por defecto hoy; hasta 3 días atrás, nunca futura, y no anterior al fin del ciclo previo (su `end_date`, o el día en que se completó si fue `COMPLETED`). `created_at` y `recorded_by` se guardan aparte. El ciclo nuevo inicia en `paidOn` y vence `paidOn.plusMonths(1)` (días 29/30/31 se ajustan al último día del mes).
- "Hoy" es SIEMPRE el día calendario de America/Bogota (nunca el día UTC), también para el job.
- Parámetros por entrenador en `coach_settings` (columnas con valores por defecto; el endpoint/pantalla para cambiarlos llega en una fase posterior): `expiring_soon_days` (5) y `expiring_soon_classes` (1) definen "por vencer" en el resumen; `max_extension_days` (60) es el tope de extensión.
- Tope de extensión: la fecha límite NUNCA puede superar `original_end_date + max_extension_days` (acumulado, sin importar cuántas extensiones se hagan); error `EXTENSION_LIMIT_EXCEEDED` (422).
- Extensiones de fecha límite: tabla `cycle_extension` (previous_end_date, new_end_date, reason, extended_by, extended_at); `cycle.original_end_date` conserva la fecha original. Solo se extiende un ciclo todavía activo.
- Cierre de ciclos vencidos: (1) toda lectura devuelve el estado EFECTIVO calculado con `CycleRules.evaluate`; (2) cualquier escritura sobre el alumno (pago, extensión) cierra antes lo vencido bajo el bloqueo de fila; (3) `CycleExpiryJob` diario 00:10 Bogotá + una pasada al arrancar, idempotente. `COMPLETED` se persiste en la misma transacción que descuenta la última clase (Fase 3).
- Email del alumno obligatorio, normalizado a minúsculas. LIMITACIÓN: un email = una cuenta en toda la plataforma (`app_user.email` único global). Si el email de un alumno ya tiene cuenta, aceptar la invitación falla con `EMAIL_ALREADY_USED` y la invitación NO se consume. El entrenador no recibe esa información al crear el alumno (no se revela qué emails existen en otros entrenadores).
- `InvitationTenantLookup` devuelve solo el `coach_id` y únicamente si la invitación sigue utilizable (ni usada, ni revocada, ni vencida): token inexistente, usado, revocado o vencido producen exactamente la misma respuesta (400 `INVALID_INVITATION`, sin cabeceras ni cuerpo distintos); hay prueba que lo compara.
- Invitaciones: token de 256 bits (`SecureRandom`), solo se guarda su SHA-256; expiran a los 7 días (`app.invitations.expiry-days`); se consumen con un `UPDATE` atómico (un solo uso aunque lleguen dos aceptaciones a la vez); reemitir revoca las anteriores. El token viaja en el cuerpo JSON (nunca en la URL) y no se escribe en logs (los records con secretos redactan `toString`; hay prueba que captura la salida).
- Al cancelar a tiempo, el alumno elige la nueva fecha en ese momento según la disponibilidad del entrenador (original → `rescheduled`, nueva `scheduled` con `rescheduled_from`).

## Suposiciones vigentes (por confirmar)
- Un alumno pertenece a un solo entrenador.
- Clases 1:1 (sin clases grupales) y duración fija por entrenador, configurable (por defecto 60 min). Falta confirmarla con el entrenador del piloto.
- Resueltas en la Fase 3 (ya son reglas): `classes_used + agendadas <= classes_included`; la clase pasada sin marcar queda "pendiente de marcar", no se descuenta sola y mantiene abierto el ciclo (el aviso al entrenador por WhatsApp llega en la Fase 5; hoy `GET /api/coach/sessions/pending` y `pendingMarks` en el resumen).

## Requisitos antes del piloto (obligatorios)
- Límite de intentos: IMPLEMENTADO en memoria (válido para UNA sola instancia): login por email (5 fallos/15 min, cuenta también emails inexistentes), login por IP (30), invitaciones preview/accept por IP (10). PENDIENTES (aprobado para el piloto, resolver después):
  - Cambiar el límite por email a email+IP, o a espera progresiva (backoff). Hoy un atacante puede bloquear 15 min el login de otra persona conociendo su email.
  - Mover los contadores a un almacén compartido (p. ej. Redis/BD) si hay más de una instancia; en memoria cada instancia cuenta por separado.
  - `SERVER_FORWARD_HEADERS_STRATEGY=native` SOLO se activa detrás de un proxy de confianza (Railway/Render/Nginx propio) que reescribe `X-Forwarded-For`. Si se activa sin proxy, cualquier cliente puede falsificar esa cabecera y esquivar el límite por IP; si NO se activa detrás de un proxy, todos los usuarios comparten la IP del proxy y se bloquean entre sí.
- Política de contraseña: mínimo 10 caracteres (máx. 72 por BCrypt). Revisar si se endurece más.
- `flyway_schema_history` debe tener RLS activado a mano (Flyway la crea): `ALTER TABLE public.flyway_schema_history ENABLE ROW LEVEL SECURITY;` Ya hecho en el proyecto Supabase `coach-platform`; repetirlo en cualquier base nueva.
- Las migraciones aplicadas son INMUTABLES. En Supabase solo está aplicada la V1 (hasta la última comprobación); V2, V3 y V4 las aplica Flyway en el próximo arranque. V4 hace `CREATE EXTENSION IF NOT EXISTS btree_gist` (verificado que funciona en Supabase). Todo cambio de esquema va en una migración nueva.

## Arquitectura del backend
Paquete raíz `com.coachplatform`. Organización por módulos de funcionalidad, no por capas técnicas:
- Funcionales: `auth`, `coach`, `students`, `billing`, `scheduling`, `notifications`. Cada uno tiene sus controladores, entidades y repositorios en su propio paquete.
- Infraestructura (usable por todos, no depende de los funcionales): `common` (Clock, errores API), `tenant`, `security`.
- Fases: `students` y `billing` en la 2, `scheduling` en la 3, `notifications` en la 5.

Reglas:
- **API pública de un módulo** = sus clases `*Service` + los tipos de su subpaquete `api` (records/DTOs). Un módulo solo usa a otro por ahí; nunca toca sus repositorios o entidades.
- Los servicios públicos reciben y devuelven solo IDs, valores simples, enums y records/DTOs pequeños; **nunca entidades**.
- Las reglas de negocio (ciclos, ventana de cancelación, reagendado, extensión de fecha) viven en clases de `<módulo>.domain`: Java simple, trabajan con valores (fechas, contadores, enums), no con entidades JPA, sin `jakarta.persistence` ni Spring web; reciben un `java.time.Clock` inyectado. El servicio traduce entre entidad y dominio.
- Interfaces (puertos) solo para fronteras externas reales: proveedor de mensajería y, más adelante, pasarela de pagos. El resto son servicios Spring normales. ÚNICA excepción interna aprobada: `billing.api.CycleSessions` (ver "Puerto interno aprobado").
- Las entidades JPA de negocio extienden `TenantScopedEntity`; no se duplica el modelo ni se crean mapeadores entre capas.
- Errores de negocio: extender `common.ApiException` (status + código estable para el frontend).
- `organization_id` solo está mapeado en `Coach`; ninguna consulta ni repositorio lo usa.
- Acceso entre tenants (JDBC sin filtro): SOLO en clases marcadas `@CrossTenantAccess` (hoy `ExpiredCycleFinder` para el job y `InvitationTenantLookup` para resolver el tenant de una invitación por el hash del token). Son package-private, solo las usa un `*Service`/`*Job`, nunca un controlador, y devuelven ids; luego se cambia de tenant con `TenantContext.callAs/runAs` (tenant derivado del servidor, nunca del cliente; abrir la transacción DENTRO del bloque). Los repositorios no usan `nativeQuery`.
- Los controladores no tocan repositorios. Las excepciones de dominio se mapean a HTTP en el módulo (p. ej. `BillingExceptionHandler`); los errores de negocio simples extienden `common.ApiException`.
- Reglas de dominio en `billing.domain` (`CycleCalendar`, `CycleRules`, `CycleState`): solo dependen del JDK, de sí mismas y de tipos `..api..`, y nunca llaman a `now()` sin `Clock`.
- Tipos públicos que cruzan módulos (enums, records) viven en `<módulo>.api`.

Pruebas ArchUnit (`ArchitectureTest`, corren con `mvn test`; verificadas introduciendo violaciones a propósito) comprueban: `..domain..` solo depende de JDK/domain/api, no usa persistencia, Spring web/http/data, controladores ni entidades y no lee el reloj del sistema; módulos solo se comunican por `*Service`/`api`; infraestructura no depende de módulos funcionales; servicios públicos no devuelven ni reciben entidades; `organization_id` no aparece en repositorios ni `@Query` ni fuera de `Coach`; componentes `@CrossTenantAccess` confinados y JDBC solo ahí; sin consultas nativas; toda entidad de negocio es `TenantScopedEntity`; controladores sin repositorios.

Pruebas: `mvn test` (unitarias + H2 + ArchUnit, rápidas) y `mvn verify` (añade las `*IT` con Testcontainers/PostgreSQL real: concurrencia de pagos e invitaciones, restricciones del esquema, aislamiento por recurso, RLS en todas las tablas salvo `flyway_schema_history`). `RealServerStatusCodesTest` usa un servidor real (puerto aleatorio) porque MockMvc NO simula el reenvío del contenedor a `/error`: sin `dispatcherTypeMatchers(ERROR).permitAll()` todo 403/400/404 se convertía en 401. Las entidades de prueba viven fuera de `com.coachplatform` (`testfixtures.*`) para no entrar al escaneo por defecto.

## Guion de prueba manual
`scripts/demo-flow.sh` recorre con curl el flujo completo (plan, disponibilidad, alumno, pago, ciclo activo, segundo pago rechazado, extensión y su tope, aceptar invitación, login del alumno, cupos, agendar, reagendar atómico, aislamiento entre alumnos, agenda, cancelar con motivo, ajustes, resumen). Usa datos inventados (un coach nuevo en cada corrida), así que solo contra una BD de desarrollo. `BASE_URL=http://127.0.0.1:8081 scripts/demo-flow.sh` (usar 127.0.0.1, no `localhost`, que puede resolver a `::1` y llegar a otro proyecto local). Probado contra un Postgres 17 desechable en Docker.

## Agenda (Fase 3): reglas implementadas
- Disponibilidad = ventanas semanales (hora local de Bogotá, `availability_rule`) cortadas en cupos de `coach_settings.class_duration_minutes` (15-180, por defecto 60); clases 1:1. `PUT /api/coach/availability` reemplaza todo y NUNCA cancela clases ya agendadas. Bloqueos (`availability_block`): al crearlos la respuesta LISTA las clases agendadas dentro; no las cancela.
- Cambiar la duración solo afecta clases futuras: cada clase guarda su `ends_at` al agendarse, y un cupo nuevo no puede solaparse con una clase existente aunque sea más larga.
- Solapes: restricción de exclusión `ex_class_session_no_overlap` (`btree_gist`, `tstzrange(starts_at, ends_at)` por `coach_id`, solo `SCHEDULED`). Verificada en Supabase (PG 17.11) y Testcontainers. Clases consecutivas sí se permiten.
- Agendar: ciclo efectivamente activo, hora futura, el alumno con anticipación >= ventana de cancelación (el entrenador no), día (Bogotá) <= `end_date`, cupo de la rejilla, sin bloqueo, sin solape, `usadas + agendadas <= incluidas`.
- Cancelación del ALUMNO (`/api/student/sessions/{id}/cancel`): solo `SCHEDULED`, antes de empezar y con >= `cancel_window_hours` (exactamente en el límite sí). Dentro de la ventana se RECHAZA y la clase sigue `SCHEDULED` (luego se marca asistida/inasistencia): no existe estado de "cancelación tardía". Con `newStartsAt` es ATÓMICO (original `RESCHEDULED` + nueva `SCHEDULED` con `rescheduled_from`; si el cupo nuevo es inválido no se cancela nada); sin él, `CANCELLED_ON_TIME` (la clase queda "debida" y se vuelve a agendar dentro del ciclo).
- Cancelación del ENTRENADOR: siempre permitida (sin ventana, incluso ya empezada), motivo OBLIGATORIO, `CANCELLED_BY_COACH`, no descuenta; `newStartsAt` opcional y atómico. **Así se perdona una cancelación tardía del alumno**: el entrenador cancela esa misma clase con motivo (antes de marcarla).
- Asistencia: desde la hora de inicio; `ATTENDED`/`NO_SHOW` descuentan 1 (la última cierra el ciclo `COMPLETED`). Se puede cambiar entre ambos mientras el ciclo esté activo (no cambia el conteo). **LIMITACIÓN CONOCIDA: marcar asistencia no se puede deshacer** (no hay vuelta a `SCHEDULED` ni cancelación posterior); si hay duda sobre perdonar una clase, cancelarla ANTES de marcarla.
- Clases pendientes de marcar = `SCHEDULED` que ya empezaron. Un ciclo NO vence mientras las tenga (`CycleRules.evaluate(estado, pendientes)`), el job no lo cierra, y la RENOVACIÓN se bloquea con `409 PENDING_SESSIONS_TO_MARK` cuya respuesta LISTA las clases (`details.pendingSessions`). `/billing/overview` y `CycleSummary` incluyen `pendingMarks`.
- Renovación el día de `end_date`: las clases del ciclo viejo que aún no empiezan pasan al ciclo nuevo y cuentan contra SU cupo; si no caben en el plan nuevo, `422 TRANSFER_EXCEEDS_PLAN` y no cambia nada.
- Reabrir: `extend` sobre un ciclo `EXPIRED` lo vuelve `ACTIVE`. Exige motivo, nueva fecha >= hoy, respetar el tope sobre `original_end_date`, ser el ÚLTIMO ciclo del alumno (si hay uno más nuevo: `REOPEN_NOT_ALLOWED`) y no haber cerrado `COMPLETED`. Queda en `cycle_extension` con `reopened = true`.
- Ajustes del entrenador: `GET/PUT /api/coach/settings` (ventana de cancelación 0-48 h, duración 15-180 min, "por vencer" días 0-60 / clases 0-100, tope de extensión 0-365 días), validados en la API y con CHECK en la base.
- `/api/student/**` (rol STUDENT) identifica al alumno SIEMPRE por el token (`student.user_id`), ningún endpoint recibe un id de alumno; la clase de otro alumno responde 404 igual que una inexistente. Las vistas del propio alumno no incluyen nombres.

## Orden de bloqueo (obligatorio, anti-deadlock)
1. Primero la fila del ALUMNO (`StudentService.lockForUpdate`, `SELECT ... FOR UPDATE`).
2. Luego, si se van a crear/mover clases, el CALENDARIO del entrenador (`CoachService.lockCalendar`: la fila de `coach_settings`).
3. Después las filas de ciclo/clase.
Lecciones aprendidas con Postgres real: (a) leer la entidad ANTES de bloquear deja una copia obsoleta en la caché de Hibernate (marcar y cancelar a la vez tenían éxito los dos): se obtiene primero el id del alumno con una consulta de proyección (`findStudentIdById`, sin cargar la entidad), se bloquea y SOLO ENTONCES se lee; (b) sin el bloqueo del calendario, dos alumnos por el mismo cupo provocan `deadlock detected` por la restricción de exclusión en vez de un rechazo limpio.

## Puerto interno aprobado (única excepción)
`billing.api.CycleSessions` (implementada por `scheduling.SchedulingCycleSessions`) es la ÚNICA interfaz interna entre módulos: billing necesita saber de las clases de un ciclo (pendientes, futuras, trasladarlas) y scheduling ya depende de billing; la interfaz evita una dependencia circular. ArchUnit solo permite esa interfaz en paquetes `api`, solo la implementa `scheduling`, y no se admiten otras. Todo lo demás son servicios Spring normales.

## Zona horaria y tipos de fecha
No usar `hibernate.jdbc.time_zone`: desplaza los `LocalTime` (disponibilidad semanal) por el desfase UTC/JVM y viola el CHECK en Postgres real (H2 lo ocultaba porque la ida y vuelta era simétrica). Los `Instant` van como `timestamptz` en UTC; `LocalDate`/`LocalTime` son valores de calendario/reloj de pared de Bogotá. `RealServerStatusCodesTest` y las `*IT` existen porque MockMvc/H2 no detectan estas clases de error.

## Fases (una a la vez; esperar visto bueno del usuario)
1. Repo, CLAUDE.md, conexión Supabase, auth y multi-tenant. La migración V1 incluye `organization` y `coach.organization_id` nullable.
2. Planes, alumnos, pagos y ciclos + pruebas.
3. Agenda, disponibilidad, cancelación/reagendado. (Implementada; pendiente de visto bueno.)
4. Vista del alumno (PWA) y marca.
5. Notificaciones WhatsApp y alertas de vencimiento.
6. Pulido y piloto (2–4 semanas).

## Convenciones
- Monorepo: `backend/` y `frontend/` (ver propuesta en conversación hasta aprobar).
- Dinero en COP como entero (`long`/`BIGINT`), sin decimales.
- Commits pequeños por fase; no avanzar de fase sin aprobación.
