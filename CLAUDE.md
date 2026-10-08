# CLAUDE.md — Plataforma MVP para entrenadores personales

Responde y comenta en español. Código, clases, tablas y variables en inglés.

`docs/prompt-inicial.md` es el documento de referencia original. Si hay contradicciones con este archivo, manda CLAUDE.md.

## Cómo trabajar en este repo (léelo primero)
**Flujo acordado con el usuario** (aplica a cada fase y a cada cambio relevante):
1. Antes de escribir código, MOSTRAR el diseño (tablas, endpoints, decisiones que necesitas del usuario) y esperar su aprobación.
2. Después, empezar por las reglas de dominio y sus pruebas, y mostrarlas antes de construir servicios/endpoints.
3. NO hacer `git commit` ni `git push` hasta que el usuario revise y lo pida. Una fase no empieza sin su visto bueno.
4. Verificar de verdad: `mvn verify` (con Docker), probar contra Postgres real, y comprobar que las pruebas detectan fallos (mutaciones). Si algo no se pudo verificar, decirlo.
5. Las decisiones se responden en lista numerada; dar una recomendación, no un catálogo. Responder en español; código, tablas y variables en inglés.

**Estado de las fases.** 1 (auth/multi-tenant) y 2 (planes, alumnos, pagos, ciclos) revisadas y subidas. 3 (agenda: eventos personalizados/semipersonalizados, asistencias, disponibilidad, bloqueos, ajustes) IMPLEMENTADA y pendiente del visto bueno del usuario. Sigue la 4 (vista del alumno PWA + marca del entrenador), luego 5 (WhatsApp) y 6 (piloto). Todavía no existe `frontend/`.

**Entorno de desarrollo (macOS, sin sudo).**
- Java 21 y Maven NO están en el PATH. Están en `~/tools/jdk-21/Contents/Home` y `~/tools/maven`. En cada comando: `export JAVA_HOME=~/tools/jdk-21/Contents/Home PATH=$JAVA_HOME/bin:~/tools/maven/bin:$PATH`. Homebrew tiene permisos rotos: no usarlo para instalar.
- `cd backend && mvn clean test` (rápido, H2 + ArchUnit) y `mvn clean verify` (añade las `*IT` con Testcontainers/PostgreSQL real; necesita Docker en marcha). Para una sola IT: `mvn verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=NombreIT` (pasar cada bandera como argumento aparte, sin variables de shell que las junten).
- Para ejecutar el backend: copiar `backend/.env.example` a `backend/.env` (gitignored; la contraseña de la BD solo vive ahí, nunca en el repo, el chat ni los logs) y `set -a; source .env; set +a; mvn spring-boot:run`. El puerto 8080 suele estar ocupado por otro proyecto local (PHP en `::1`): usar `SERVER_PORT=8081` y `http://127.0.0.1:...` (no `localhost`).
- Para probar de punta a punta sin tocar Supabase: Postgres desechable en Docker (`docker run -d --name coach-demo-pg -e POSTGRES_PASSWORD=<desechable> -p 127.0.0.1::5432 postgres:17-alpine`, leer el puerto con `docker port`), levantar el backend contra él y correr `scripts/demo-flow.sh`; luego borrar el contenedor.
- Si Docker responde `permission denied` en el socket, es una restricción del entorno del agente, no del código.

**Supabase.** Proyecto `coach-platform`, ref `rnkayaatdssplqwsleec`, región sa-east-1 (PostgreSQL 17). Hay un MCP de Supabase (`.mcp.json`) para consultar y validar SQL. Estado (última comprobación): SOLO la V1 está aplicada; V2, V3 y V4 las aplica Flyway en el próximo arranque contra esa BD. Las migraciones aplicadas son inmutables (checksum de V1: -2055938445). Para validar SQL nuevo en Supabase sin dejar nada: ejecutarlo dentro de `BEGIN; ...; ROLLBACK;`. Conexión de la app: pooler en modo SESIÓN (puerto 5432).

**Git.** Remoto `origin` = `josemuesc/coach-platform` (rama `main`). La cuenta de Git configurada por defecto (`iasynthetix-ia`) no tiene permiso de escritura en ese repo (da 403 al hacer push): lo resuelve el usuario con su token/credencial. La V4 antigua (modelo 1:1) quedó subida pero nunca se aplicó a una base real; la V4 vigente es la de eventos y asistencias. Si alguna BD local tuvo la V4 vieja, hay que recrearla (checksum distinto).

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
- Hay clases personalizadas (1 alumno) y semipersonalizadas (varios): ver "Agenda". Duración fija por entrenador, configurable (por defecto 60 min); falta confirmarla con el entrenador del piloto. NO hay listas de espera ni clases recurrentes automáticas (fuera de alcance).
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
`scripts/demo-flow.sh` recorre con curl el flujo completo (plan, disponibilidad, alumno, pago, ciclo activo, segundo pago rechazado, extensión y su tope, aceptar invitación, login del alumno, cupos, agendar, reagendar atómico, aislamiento entre alumnos, eventos personalizados y semi (compartir, modalidad, capacidad, evento lleno, override), agenda con asistentes, cancelar una asistencia y un evento completo, ajustes, resumen). Usa datos inventados (un coach nuevo en cada corrida), así que solo contra una BD de desarrollo. `BASE_URL=http://127.0.0.1:8081 scripts/demo-flow.sh` (usar 127.0.0.1, no `localhost`, que puede resolver a `::1` y llegar a otro proyecto local). Probado contra un Postgres 17 desechable en Docker.

## Agenda (Fase 3): eventos y asistencias
**Modelo.** Hay dos conceptos distintos:
- **Evento** (`class_session`): el bloque de tiempo del entrenador (`starts_at`, `ends_at`, `modality`, `capacity`, `status` SCHEDULED/CANCELLED). Lo crea el primer asistente y toma de él modalidad y capacidad.
- **Asistencia** (`session_attendance`): el lugar de UN alumno en un evento (6 estados, `rescheduled_from`, auditoría de override). El consumo de clases del ciclo, la ventana de cancelación y el marcado son POR ASISTENCIA, nunca por evento.

**Modalidad y capacidad.**
- `plan.modality` (PERSONALIZED | SEMI_PERSONALIZED) es OBLIGATORIA al crear/editar un plan (los planes que ya existían quedaron PERSONALIZED por la migración V4). No hay lógica de precios por modalidad: los precios son planes distintos.
- El ciclo guarda una COPIA de la modalidad del plan al abrirse (`cycle.modality`): editar el plan nunca cambia un ciclo en curso.
- Personalizado = capacidad 1, no configurable. Semipersonalizado = capacidad 2-10: un evento NUEVO toma `coach_settings.default_group_capacity` (2-10, por defecto 4) vigente; cambiar ese default solo afecta eventos nuevos. El entrenador puede cambiar la capacidad de UN evento (2-10) solo ANTES de que empiece y nunca por debajo de los asistentes actuales (`CAPACITY_BELOW_OCCUPANCY`, con el número de asistentes en el mensaje).
- Reglas al pedir un horario (`BookingRules`): bloque sin evento -> crea un evento con la modalidad del alumno; evento personalizado ocupado -> `SLOT_TAKEN`; evento de la otra modalidad -> `MODALITY_MISMATCH`; semi lleno -> `EVENT_FULL`; solapado de otro largo (tras cambiar la duración) -> `SLOT_TAKEN`; ya está en el evento -> `ALREADY_BOOKED`. Reagendar aplica las mismas reglas, dentro del ciclo y con la ventana.
- **Override** (solo el entrenador): permite entrar a un evento lleno, de otra modalidad o personalizado ocupado, con motivo OBLIGATORIO; queda en la asistencia (`override`, `override_reason`, `override_by`) y se ve en la agenda. NO relaja ciclo activo, fecha límite, cuota, disponibilidad ni bloqueos. Un override innecesario no se registra. El alumno nunca puede (el campo se ignora). Un override puede dejar un evento con más asistentes que su capacidad.
- Visibilidad para el alumno (`SlotCatalog`): cada bloque con modalidad y "ocupados de capacidad", filtrado por su plan: personalizado ve solo bloques vacíos; semi ve bloques vacíos (con la capacidad por defecto vigente) y eventos semi con cupo. Ningún endpoint de alumno devuelve nombres ni ids de otros asistentes (hay prueba que busca nombre, id, email e id de asistencia del otro alumno en todas las respuestas). Solo la agenda del entrenador lista asistentes.

**Cancelaciones.**
- ALUMNO (`/api/student/sessions/{id}/cancel`): solo afecta su asistencia; el evento sigue si quedan otros. Solo `SCHEDULED`, antes de empezar y con >= `cancel_window_hours` (exactamente en el límite sí). Dentro de la ventana se RECHAZA y la asistencia sigue `SCHEDULED` (luego se marca): no existe un estado de "cancelación tardía". Con `newStartsAt` es ATÓMICO (original `RESCHEDULED` + nueva `SCHEDULED` con `rescheduled_from`; si el cupo nuevo es inválido, no se cancela nada); sin él, `CANCELLED_ON_TIME`. Mover al mismo horario se rechaza (`ALREADY_BOOKED`).
- ENTRENADOR, una asistencia (`/api/coach/attendances/{id}/cancel`): siempre permitida (sin ventana, incluso ya empezada), motivo OBLIGATORIO, `CANCELLED_BY_COACH`, no descuenta; `newStartsAt` opcional y atómico. **Así se perdona una cancelación tardía del alumno** (antes de marcarla).
- ENTRENADOR, evento completo (`/api/coach/events/{id}/cancel`): motivo obligatorio; todas las asistencias `SCHEDULED` pasan a `CANCELLED_BY_COACH` y nadie descuenta; la respuesta LISTA los alumnos afectados. Las asistencias ya marcadas no se tocan y entonces el evento NO se cancela. No hay "mover el evento entero": el entrenador mueve asistencia por asistencia.
- Un evento sin asistencias vivas (SCHEDULED/ATTENDED/NO_SHOW) pasa solo a `CANCELLED` (nunca se borra: las asistencias canceladas lo referencian y queda el historial) y libera su horario para otra reserva, incluso de otra modalidad. Nunca se cancela solo si tiene una clase marcada.

**Asistencia.** `POST /api/coach/attendances/{id}/mark` y `POST /api/coach/events/{id}/mark` (varios alumnos del evento, TODO O NADA, alumnos bloqueados en orden de id). Desde la hora de inicio; `ATTENDED`/`NO_SHOW` descuentan 1 del ciclo de ESE alumno (la última cierra el ciclo `COMPLETED`); se puede cambiar entre ambos mientras el ciclo esté activo (no cambia el conteo). **LIMITACIÓN CONOCIDA: marcar asistencia no se puede deshacer** (no vuelve a `SCHEDULED` ni se cancela después); si hay duda sobre perdonar, cancelar ANTES de marcar.

**Pendientes de marcar** = asistencias `SCHEDULED` cuyo evento ya empezó (dos alumnos en un mismo evento son DOS pendientes). Un ciclo NO vence mientras las tenga (`CycleRules.evaluate(estado, pendientes)`), el job no lo cierra, y la RENOVACIÓN se bloquea con `409 PENDING_SESSIONS_TO_MARK` cuya respuesta LISTA las asistencias (`details.pendingSessions`). `/billing/overview` y `CycleSummary` incluyen `pendingMarks`.

**Renovación el día de `end_date`.** Las asistencias del ciclo viejo cuyo evento aún no empieza pasan al ciclo nuevo y cuentan contra SU cupo (`422 TRANSFER_EXCEEDS_PLAN` si no caben). Si la modalidad del plan nuevo difiere de la de esos eventos, el pago se rechaza con `409 MODALITY_CONFLICT_ON_RENEWAL` y una explicación con las dos salidas del entrenador: (1) cancelar esas clases sin castigo y repetir el pago; (2) repetir el pago con `overrideModality=true` y `overrideReason`: se trasladan y quedan marcadas como override. Sin motivo: `422 OVERRIDE_REASON_REQUIRED`.

**Reabrir** un ciclo vencido: `extend` sobre un ciclo `EXPIRED` lo vuelve `ACTIVE`. Exige motivo, nueva fecha >= hoy, respetar el tope sobre `original_end_date`, ser el ÚLTIMO ciclo del alumno (si hay uno más nuevo: `REOPEN_NOT_ALLOWED`) y no haber cerrado `COMPLETED`. Queda en `cycle_extension` con `reopened = true`.

**Calendario.** Disponibilidad = ventanas semanales (hora local de Bogotá) cortadas en cupos de `class_duration_minutes` (15-180, por defecto 60). `PUT /api/coach/availability` reemplaza todo y NUNCA cancela nada ya agendado. Bloqueos: al crearlos la respuesta LISTA los eventos (con sus asistentes) que quedan dentro; no los cancela. Cambiar la duración solo afecta lo futuro: cada evento guarda su `ends_at`, y un cupo nuevo no puede solaparse con un evento existente aunque sea más largo (ni unirse a él). Solapes: restricción de exclusión `ex_class_session_no_overlap` (`btree_gist`, `tstzrange` por `coach_id`, solo eventos `SCHEDULED`), verificada en Supabase (PG 17.11) y Testcontainers; varios alumnos COMPARTEN un evento, nunca crean eventos solapados.

**Ajustes del entrenador** (`GET/PUT /api/coach/settings`, rangos validados en la API y con CHECK en la base): ventana de cancelación 0-48 h, duración 15-180 min, capacidad de grupo por defecto 2-10, "por vencer" (días 0-60 / clases 0-100), tope de extensión 0-365 días.

**Alumno.** `/api/student/**` (rol STUDENT) identifica al alumno SIEMPRE por el token (`student.user_id`); ningún endpoint recibe un id de alumno; la asistencia de otro alumno responde 404 igual que una inexistente.

**Idea futura (NO construir):** ventanas de disponibilidad marcadas como "solo grupal" o "personalizado permitido", por si los semipersonalizados fragmentan los bloques del entrenador (un bloque semi a medio llenar impide los personalizados en ese horario).

## Orden de bloqueo (obligatorio, anti-deadlock)
1. Las filas de ALUMNO (`StudentService.lockForUpdate`, `SELECT ... FOR UPDATE`), en orden ascendente de id si son varios.
2. El CALENDARIO del entrenador (`CoachService.lockCalendar`: la fila de `coach_settings`), SOLO si puede crearse un evento: se lee primero (solo ids, sin cargar entidades); si no hay nada ahí se toma el bloqueo del calendario y se VUELVE a comprobar antes de crear. En un traslado (reagendar) se toma siempre antes que cualquier bloqueo de evento.
3. Las filas de EVENTO (`SELECT ... FOR UPDATE`), en orden ascendente de id; al agregar, quitar o contar asistencias de un evento y al cambiar su capacidad.
4. Después las filas de asistencia y de ciclo.
Si entre la lectura y el bloqueo el evento cambió (p. ej. se canceló), la operación se repite sola (ver "Reintento de conflictos concurrentes") y solo si sigue ocurriendo tras 3 intentos el cliente recibe `409 CONCURRENT_CHANGE`; el orden de bloqueo nunca se invierte.
Lecciones aprendidas con Postgres real: (a) leer la entidad ANTES de bloquear deja una copia obsoleta en la caché de Hibernate: se obtiene primero el id con una consulta de proyección, se bloquea y SOLO ENTONCES se lee; (b) sin el bloqueo del calendario, dos reservas por horarios solapados provocan `deadlock detected` por la restricción de exclusión; (c) dos traslados cruzados (A de E1 a E2 y B de E2 a E1) se bloquearían al revés: por eso los eventos se bloquean siempre ordenados por id. Las pruebas de concurrencia (`SchedulingConcurrencyIT`: personalizado vs semi por el mismo bloque, último cupo, capacidad vs unirse, traslados cruzados, cancelar evento vs unirse) se verificaron quitando cada bloqueo a propósito: fallan.

## Puerto interno aprobado (única excepción)
`billing.api.CycleSessions` (implementada por `scheduling.SchedulingCycleSessions`) es la ÚNICA interfaz interna entre módulos: billing necesita saber de las ASISTENCIAS de un ciclo (pendientes, futuras con la modalidad de su evento, trasladarlas) y scheduling ya depende de billing; la interfaz evita una dependencia circular. Habla de asistencias, nunca de eventos. ArchUnit solo permite esa interfaz en paquetes `api`, solo la implementa `scheduling`, y no se admiten otras. Todo lo demás son servicios Spring normales.

## Cuerpos de petición estrictos (Jackson 3)
Spring Boot 4 trae Jackson 3, que rechaza con 400 un `boolean`/`int` AUSENTE en un record. NO se relaja globalmente (hay una prueba que lo comprueba). Regla para todo cuerpo de petición (records `*Command`, `*Input`, `*Request`, `*Item`, `UpdateCoachSettings`, fuera de `..domain..`; la regla de ArchUnit `requestBodiesHaveNoPrimitiveComponents` lo hace cumplir):
- Sin primitivos: se usan envoltorios (`Integer`, `Long`, `Boolean`).
- OBLIGATORIO -> envoltorio con `@NotNull` (más `@Min/@Max/@Positive`/`@NotBlank`): un campo ausente es un 400 de validación, nunca un 0 o `false` silencioso.
- OPCIONAL -> envoltorio con valor por defecto EXPLÍCITO en el constructor compacto del record (p. ej. `override`/`overrideModality` valen `false` si se omiten; `paidOn` vale hoy).
- Mandatorios confirmados con pruebas (`RequestValidationTest`): pago sin `amountCop`/`planId`/`method`; plan sin `name`/`classesIncluded`/`priceCop`/`modality`; alumno sin `email`/`fullName`; actualización de alumno sin `data`; ajustes del entrenador con cualquier campo ausente (el PUT reemplaza todo); cambio de capacidad sin `capacity`; ventana de disponibilidad sin `dayOfWeek`/`start`/`end`; reserva sin `startsAt`.
- **`amountCop` del pago es OBLIGATORIO**: siempre se declara lo realmente recibido, no se asume el precio del plan (el cliente puede precargarlo desde el plan). Cambio respecto al diseño original, donde era opcional.

## Reintento de conflictos concurrentes
`CONCURRENT_CHANGE` (algo leído quedó obsoleto antes de poder bloquearlo, p. ej. un evento cancelado entre la lectura y el bloqueo) se reintenta AUTOMÁTICAMENTE en el servidor: la operación completa se repite desde cero en una transacción NUEVA, hasta 3 intentos en total con una pausa mínima (10-30 ms, con algo de azar), antes de devolver el 409 (`common.ConflictRetry`). Solo se reintenta esa excepción: los rechazos de negocio y cualquier otro error suben de inmediato. Por eso `reservar` (alumno y entrenador), `cancelar asistencia` (alumno y entrenador) y `cancelar evento` NO son `@Transactional` sino que abren su propia transacción en cada intento (`SchedulingService.inFreshTransactions`); no llamarlos desde dentro de otra transacción.

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
