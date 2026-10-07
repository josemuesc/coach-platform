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
- "Un solo ciclo activo por alumno" se garantiza con un índice único parcial en la base de datos (`UNIQUE (student_id) WHERE status = 'active'`), además de la validación en el servicio.
- Al cancelar a tiempo, el alumno elige la nueva fecha en ese momento según la disponibilidad del entrenador (original → `rescheduled`, nueva `scheduled` con `rescheduled_from`).

## Suposiciones vigentes (por confirmar)
- classes_used + clases agendadas ≤ classes_included.
- Clase pasada sin marcar queda "pendiente de marcar", se avisa al profe y no se descuenta sola.
- Un alumno pertenece a un solo entrenador.
- Duración de clase fija por entrenador (configurable), aún sin definir.

## Requisitos antes del piloto (obligatorios)
- Límite de intentos de login (rate limiting / bloqueo temporal por cuenta e IP). Pendiente: hoy `/api/auth/login` no lo tiene.

## Arquitectura del backend
Paquete raíz `com.coachplatform`. Organización por módulos de funcionalidad, no por capas técnicas:
- Funcionales: `auth`, `coach`, `students`, `billing`, `scheduling`, `notifications`. Cada uno tiene sus controladores, entidades y repositorios en su propio paquete.
- Infraestructura (usable por todos, no depende de los funcionales): `common` (Clock, errores API), `tenant`, `security`.
- Fases: `students` y `billing` en la 2, `scheduling` en la 3, `notifications` en la 5.

Reglas:
- **API pública de un módulo** = sus clases `*Service` + los tipos de su subpaquete `api` (records/DTOs). Un módulo solo usa a otro por ahí; nunca toca sus repositorios o entidades.
- Los servicios públicos reciben y devuelven solo IDs, valores simples, enums y records/DTOs pequeños; **nunca entidades**.
- Las reglas de negocio (ciclos, ventana de cancelación, reagendado, extensión de fecha) viven en clases de `<módulo>.domain`: Java simple, trabajan con valores (fechas, contadores, enums), no con entidades JPA, sin `jakarta.persistence` ni Spring web; reciben un `java.time.Clock` inyectado. El servicio traduce entre entidad y dominio.
- Interfaces (puertos) solo para fronteras externas reales: proveedor de mensajería y, más adelante, pasarela de pagos. El resto son servicios Spring normales.
- Las entidades JPA de negocio extienden `TenantScopedEntity`; no se duplica el modelo ni se crean mapeadores entre capas.
- Errores de negocio: extender `common.ApiException` (status + código estable para el frontend).
- `organization_id` solo está mapeado en `Coach`; ninguna consulta ni repositorio lo usa.

Pruebas ArchUnit (`ArchitectureTest`, corren con `mvn test`) verifican: `..domain..` no depende de jakarta.persistence, Hibernate, Spring web/http/data, controladores ni entidades; módulos solo se comunican por `*Service`/`api`; infraestructura no depende de módulos funcionales; servicios públicos no devuelven ni reciben entidades; `organization_id` no aparece en repositorios ni `@Query` ni fuera de `Coach`.

## Fases (una a la vez; esperar visto bueno del usuario)
1. Repo, CLAUDE.md, conexión Supabase, auth y multi-tenant. La migración V1 incluye `organization` y `coach.organization_id` nullable.
2. Planes, alumnos, pagos y ciclos + pruebas.
3. Agenda, disponibilidad, cancelación/reagendado.
4. Vista del alumno (PWA) y marca.
5. Notificaciones WhatsApp y alertas de vencimiento.
6. Pulido y piloto (2–4 semanas).

## Convenciones
- Monorepo: `backend/` y `frontend/` (ver propuesta en conversación hasta aprobar).
- Dinero en COP como entero (`long`/`BIGINT`), sin decimales.
- Commits pequeños por fase; no avanzar de fase sin aprobación.
