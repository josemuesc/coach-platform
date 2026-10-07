# Prompt para Claude Code: MVP plataforma para entrenadores personales

## 0. Cómo quiero que trabajes
- Lee TODO este documento antes de escribir código.
- Primero crea un `CLAUDE.md` en la raíz con un resumen de las reglas de negocio (sección 3), el stack (sección 2) y las convenciones del proyecto, para que no se pierdan entre sesiones.
- Luego propón la estructura del repositorio y un plan por fases (sección 9). Espera mi aprobación antes de pasar a la siguiente fase.
- La lógica de ciclos y cancelaciones debe tener pruebas automáticas antes de construir encima de ella.
- Si algo no está definido o algo de lo que pido entra en conflicto, pregúntame antes de asumir. Las suposiciones que ya hice están marcadas en la sección 10.
- Responde y comenta en español. El código, los nombres de clases, tablas y variables van en inglés.

## 1. Producto y contexto
Una plataforma (PWA) para entrenadores personales que reemplaza hojas de cálculo y chats desordenados. El entrenador gestiona alumnos, agenda, paquetes de clases, pagos y seguimiento. El alumno ve su ciclo, agenda y cancela clases, y ve su progreso.

- **Piloto:** un amigo entrenador personal (Smart Fit, Colombia) con 10 alumnos. Sirve para pulir el MVP, no para validar el negocio por sí solo.
- **Visión comercial:** la plataforma es genérica y multi-entrenador. Cada entrenador es un cliente que paga una mensualidad por usar el software. El alumno ve la marca de SU entrenador.
- **Mercado:** Colombia. Moneda COP, zona horaria America/Bogota, idioma español.
- **Prioridad:** simplicidad. No competir en cantidad de funciones sino en ser la más simple y adaptada a Colombia (WhatsApp como canal principal).

## 2. Stack decidido
- **Backend:** Java + Spring Boot, Spring Security con JWT, Flyway para migraciones.
- **Base de datos:** PostgreSQL alojada en Supabase (plan gratis en piloto, Pro cuando cobre). Usar Supabase SOLO como Postgres; la autenticación y las reglas de negocio las maneja Spring Boot.
  - Conectar por el **pooler** de Supabase (la conexión directa suele ser solo IPv6).
  - Activar **Row Level Security en todas las tablas sin políticas públicas** (o desactivar la API REST de Supabase) para que las tablas no queden expuestas con la llave pública.
- **Frontend:** React + Vite + Tailwind, mobile-first, como **PWA** instalable (manifest, service worker). Más adelante se puede empaquetar con Capacitor si se necesitan las tiendas.
- **Multi-tenant:** una sola base de datos, columna `coach_id` en todas las tablas de negocio. Todo acceso debe filtrar por tenant desde el servidor (nunca confiar en el cliente).
- **Fechas:** guardar instantes en UTC; las fechas de ciclo son `LocalDate` en la zona del entrenador (America/Bogota). Mostrar en hora de Bogotá.
- **Despliegue:** backend en Railway/Render/VPS pequeño; frontend estático. Mantener todo barato.

## 3. Reglas de negocio (ya definidas con el entrenador del piloto)

### Planes (paquetes)
Cada entrenador configura sus planes: nombre, número de clases, precio. Ejemplo real del piloto (COP):
- 8 clases (2 días/semana): 520.000
- 12 clases (3 días/semana): 620.000
- 16 clases (4 días/semana): 720.000

### Ciclo
- Un ciclo nace cuando se registra un pago. **Inicia el día del pago** y vence un mes después (`fecha_pago.plusMonths(1)`; ejemplo: pago 6 de octubre, vence 6 de noviembre).
- Si el alumno paga tarde, el ciclo nuevo empieza el día que paga (ej.: vence el 6 de nov, paga el 12 → ciclo del 12 de nov al 12 de dic). Entre un ciclo y otro puede haber días sin clases; es normal.
- **El ciclo se cierra por lo primero que ocurra:**
  - Se agotan las clases → cierre `completado` (aunque no haya llegado la fecha límite).
  - Llega la fecha límite con clases sin usar → cierre `vencido`; las clases sobrantes **se pierden**. No hay arrastre al mes siguiente.
- **Un solo ciclo activo por alumno.** No se pagan clases por adelantado: el alumno solo paga un ciclo nuevo cuando agotó o venció el anterior. El sistema bloquea (o advierte claramente) un pago nuevo si hay ciclo activo.
- Un alumno sin ciclo activo no puede agendar clases.

### Clases (sesiones) y estados
Estados: `scheduled`, `attended` (vista), `cancelled_on_time`, `rescheduled`, `no_show`, `cancelled_by_coach`.
- Una clase "vista" descuenta 1 clase del ciclo. Se descuenta cuando el profe marca asistencia, y también cuando la clase queda como vista por cancelación tardía o inasistencia.
- **Ventana de cancelación: 2 horas** antes de la clase (configurable por entrenador en `coach_settings`).
  - Si el alumno cancela con 2 horas o más de anticipación: la clase no se descuenta y se **reagenda dentro del mismo ciclo**; la nueva fecha debe ser anterior o igual a la fecha de vencimiento del ciclo (el sistema bloquea fechas posteriores).
  - Con menos de 2 horas: no se puede cancelar (el botón desaparece) y la clase queda como **vista**. Faltar sin avisar también cuenta como vista.
- **La validación de la ventana se hace SIEMPRE en el servidor**, con la hora del servidor, no en el frontend.
- **Si cancela el profe** (enfermedad, imprevisto): no se descuenta la clase; se reagenda dentro del ciclo. Si el ciclo va a vencer antes de poder reponerla, el profe puede **extender manualmente la fecha límite** del ciclo (con registro de quién y cuándo).

## 4. Roles y funciones del MVP

### Entrenador (COACH)
- Registro, login, configuración de marca (nombre, logo, color principal).
- Configuración: ventana de cancelación, planes.
- Alumnos: crear e invitar por enlace; lista con estado (activo, por vencer, sin ciclo).
- Disponibilidad semanal y agenda (vista por día/semana).
- Marcar asistencia, cancelar/reagendar clases, extender fecha límite de un ciclo.
- Registrar pagos manualmente (Nequi, transferencia, efectivo): esto abre el ciclo.
- Ver pagos hechos y lista de alumnos con pago pendiente.
- Registrar medidas del alumno y notas por sesión.

### Alumno (STUDENT)
- Ver la marca de su entrenador.
- Ver su ciclo: clases restantes y fecha límite.
- Agendar según disponibilidad; cancelar/reagendar respetando la regla de 2 horas.
- Ver historial de clases, medidas y gráfica simple de evolución.

## 5. Fuera del alcance (NO construir todavía)
Pagos en línea, push nativo, rutinas estructuradas y fotos de progreso, nutrición, gimnasios con varios entrenadores, funciones con IA, app nativa, cobro de la suscripción a los entrenadores (por ahora el entrenador del piloto usa la plataforma gratis), módulo para organizaciones o gimnasios (reportes de cumplimiento de sus entrenadores).

## 6. Modelo de datos inicial (propuesta, ajústalo si ves algo mejor)
- `organization`: `id`, `name`, `created_at`.
- `coach` (tenant): nombre, marca (logo, color), contacto, zona horaria, `organization_id` NULLABLE (FK a `organization`).
  - En el MVP esto es solo preparación: ningún entrenador tendrá organización (queda en null), no se construye interfaz ni lógica para esto, y el aislamiento multi-tenant sigue siendo por `coach_id`. `organization_id` no cambia ninguna regla de acceso.
- `coach_settings`: `cancel_window_hours` (default 2), banderas para futuras políticas.
- `app_user`: credenciales, rol (COACH/STUDENT), `coach_id`.
- `student`: datos básicos, teléfono WhatsApp, consentimiento de mensajes y de tratamiento de datos.
- `plan`: `coach_id`, nombre, `classes_included`, precio, activo.
- `cycle`: `student_id`, `plan_id`, `start_date`, `end_date`, `classes_included`, `classes_used`, `status` (activo/cerrado), `close_reason` (completado/vencido), `closed_at`, `extended_by`/`extended_at` si se extendió.
- `payment`: `student_id`, `cycle_id`, monto, fecha, método, registrado por.
- `availability`: franjas semanales por entrenador.
- `session`: `student_id`, `cycle_id`, fecha/hora, estado, `rescheduled_from` (referencia a la clase original), motivo.
- `measurement`: medidas del alumno por fecha.
- `session_note`: notas del profe.
- `notification`: tipo, destinatario, plantilla, payload, estado (pendiente/enviada/fallida), intentos, fechas.

## 7. Notificaciones automáticas (WhatsApp Cloud API)
Objetivo: que las alertas salgan solas, sin que el entrenador las envíe a mano.
- Un proceso programado (`@Scheduled`, con ShedLock si hay varias instancias) revisa eventos, crea filas en `notification` y un sender las envía con reintentos.
- Crear una **interfaz de proveedor de mensajería** (para poder cambiar de proveedor) con una implementación para WhatsApp Cloud API y otra "mock" que escriba a log en desarrollo.
- Mensajes iniciados por el negocio fuera de la ventana de 24 h requieren **plantillas aprobadas por Meta** (categoría utilidad). Deja los textos de las plantillas en un archivo documentado para registrarlas.
- Un solo número de WhatsApp para toda la plataforma; el mensaje menciona el nombre del entrenador.
- Automatizaciones:
  1. Recordatorio de clase unas 3 horas antes (para poder cancelar dentro de la ventana).
  2. Aviso al alumno cuando le queda 1 clase.
  3. Aviso 3 a 5 días antes del vencimiento si aún tiene clases sin usar.
  4. Aviso de ciclo completado o vencido, con invitación a renovar.
  5. Confirmación al registrar un pago.
  6. Alertas al entrenador: alumnos sin ciclo, cancelaciones, pagos pendientes.
- **Consentimiento:** no enviar mensajes a un alumno sin opt-in registrado. Las medidas corporales son datos de salud: pedir autorización de tratamiento de datos (Ley 1581 de Colombia).
- Respaldo: botón manual con enlace `wa.me` y mensaje armado si falla el envío automático.

## 8. Seguridad y calidad
- Aislamiento por tenant en TODAS las consultas (idealmente con un filtro central, no repetido a mano).
- Validaciones de negocio en el servidor; el frontend solo refleja.
- Contraseñas con hash seguro, JWT con expiración, invitaciones con token de un solo uso.
- **Pruebas unitarias obligatorias** para el servicio de ciclos: cálculo de fecha de vencimiento (incluye pagos los días 29, 30 y 31), cierre por completado vs vencido, un solo ciclo activo, bloqueo de pago con ciclo activo, ventana de 2 horas, reagendar solo dentro del ciclo, extensión por el profe.
- Migraciones con Flyway; nada de cambios manuales en la base.

## 9. Fases (avanza una a la vez y espera mi visto bueno)
1. Estructura del repo, `CLAUDE.md`, conexión a Supabase, autenticación y multi-tenant.
2. Planes, alumnos, pagos y ciclos, con sus pruebas.
3. Agenda, disponibilidad y reglas de cancelación/reagendado.
4. Vista del alumno (PWA) y marca del entrenador.
5. Notificaciones automáticas por WhatsApp y alertas de vencimiento.
6. Pulido y piloto con el entrenador (2 a 4 semanas).

## 10. Suposiciones y pendientes
Suposiciones que hice (confírmalas o propón algo mejor):
- Un alumno no puede tener agendadas más clases activas que las que le quedan en el ciclo (`classes_used` + clases agendadas ≤ `classes_included`).
- Si el profe olvida marcar la asistencia de una clase pasada, queda "pendiente de marcar" y se le avisa; no se descuenta sola hasta que la resuelva.
- Un alumno pertenece a un solo entrenador en el MVP.
- Duración fija de clase por entrenador (configurable); aún no definida.

Pendientes por definir con el entrenador del piloto:
- Duración típica de la clase y manera de definir los horarios.
- Si sus alumnos usan más iPhone o Android (afecta el flujo de instalación de la PWA).

## 11. Contexto comercial (solo para tener en cuenta el diseño, NO implementar ahora)
Modelo previsto: mensualidad al entrenador según número de alumnos activos (gratis hasta ~3; planes de pago por tramos). Deja la estructura lista para poder agregar un campo de plan/suscripción del entrenador más adelante.

Nota: a futuro, una organización podría ver únicamente métricas de cumplimiento de sus entrenadores (clases dadas vs. agendadas, cancelaciones del profe), nunca pagos, precios, medidas ni datos personales de los alumnos, y solo con aceptación explícita del entrenador.
