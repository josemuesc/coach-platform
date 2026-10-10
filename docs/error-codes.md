# Códigos de error estables

Toda respuesta de error lleva `{"code": "...", "message": "..."}` (a veces `details`). El frontend decide por `code`, nunca por el
texto; el mensaje en español de esta tabla es el que se muestra al usuario. Los mensajes que llegan del servidor están en inglés.

> `ErrorCodesDocTest` compara esta tabla con el código: falla si el servidor puede enviar un código que no está aquí, si aquí
> hay uno que ningún código produce, o si el HTTP de un código declarado en `ApiException` o en los manejadores no coincide. LIMITACIÓN:
> el HTTP de los códigos de los enums `*RuleException.Code` se decide en cada manejador (`switch`) y la prueba no lo verifica.

## Generales y de acceso
| Código | HTTP | Mensaje en español |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Revisa los datos enviados: falta un campo obligatorio o tiene un valor no válido. |
| `INVALID_CREDENTIALS` | 401 | Correo o contraseña incorrectos. |
| `TOO_MANY_ATTEMPTS` | 429 | Demasiados intentos. Espera unos minutos e inténtalo de nuevo. |
| `INVALID_INVITATION` | 400 | La invitación no es válida o ya venció. Pide al entrenador un enlace nuevo. |
| `REGISTRATION_CLOSED` | 403 | El registro de entrenadores está cerrado. |
| `INVALID_RESET_LINK` | 400 | El enlace para cambiar la contraseña no es válido, ya se usó o venció. Pide al entrenador uno nuevo. (Misma respuesta en cualquiera de esos casos.) |
| `INVALID_SESSION` | 401 | Tu sesión terminó porque la contraseña cambió. Inicia sesión de nuevo. |
| `EMAIL_ALREADY_USED` | 409 | Ese correo ya tiene una cuenta en la plataforma. (Para un alumno menor se usa `GUARDIAN_EMAIL_IN_USE`.) |
| `CONCURRENT_CHANGE` | 409 | Otra persona cambió esto al mismo tiempo. Vuelve a intentarlo. |

## Planes, alumnos y ciclos
| Código | HTTP | Mensaje en español |
|---|---|---|
| `PLAN_NOT_FOUND` | 404 | No se encontró el plan. |
| `PLAN_NAME_EXISTS` | 409 | Ya tienes un plan con ese nombre. |
| `STUDENT_NOT_FOUND` | 404 | No se encontró el alumno. |
| `STUDENT_EMAIL_EXISTS` | 409 | Ya tienes un alumno con ese correo. |
| `GUARDIAN_EMAIL_IN_USE` | 409 | Este correo ya tiene una cuenta. Cada alumno necesita un correo distinto; si eres el acudiente, avisa a tu entrenador para que registre otro. (Aplica a un alumno menor, al crearlo, al cambiar el correo del representante o al aceptar la invitación si el representante ya tiene cuenta con otro entrenador. La respuesta lleva este texto en `details.message`.) |
| `EMAIL_LOCKED` | 409 | El correo no se puede cambiar porque el alumno ya aceptó la invitación. |
| `STUDENT_ALREADY_HAS_ACCOUNT` | 409 | El alumno ya tiene su cuenta creada. |
| `STUDENT_HAS_NO_ACCOUNT` | 409 | El alumno todavía no aceptó la invitación, así que no hay contraseña que restablecer. Reenvía la invitación. |
| `EMAIL_REQUIRED` | 422 | Un alumno mayor de edad necesita su propio correo (es su usuario de acceso). |
| `ACCOUNT_SUSPENDED` | 403 | La cuenta está suspendida porque se revocó la autorización de tratamiento de datos. |
| `CYCLE_NOT_FOUND` | 404 | No se encontró el ciclo. |
| `ACTIVE_CYCLE_EXISTS` | 409 | El alumno ya tiene un ciclo activo. Podrás registrar el pago desde la fecha límite o cuando use sus clases. |
| `CYCLE_NOT_ACTIVE` | 409 | El ciclo ya no está activo. |
| `INVALID_PAYMENT_DATE` | 422 | La fecha de pago no es válida (hasta 3 días atrás, nunca futura, y no anterior al fin del ciclo previo). |
| `INVALID_EXTENSION` | 422 | La nueva fecha límite no es válida. |
| `EXTENSION_LIMIT_EXCEEDED` | 422 | Se superó el máximo de días de extensión permitido. |
| `INVALID_PLAN` | 422 | El plan no es válido para esta operación. |
| `PENDING_SESSIONS_TO_MARK` | 409 | Hay clases ya empezadas sin marcar. Márcalas antes de renovar. La respuesta lista cuáles son. |
| `TRANSFER_EXCEEDS_PLAN` | 422 | Las clases ya agendadas no caben en el plan nuevo. |
| `MODALITY_CONFLICT_ON_RENEWAL` | 409 | El plan nuevo tiene otra modalidad que las clases ya agendadas. Cancélalas sin castigo o repite el pago autorizando el cambio con un motivo. |
| `OVERRIDE_REASON_REQUIRED` | 422 | Indica el motivo para autorizar el cambio. |
| `INVALID_PAYMENT_REFERENCE` | 422 | La referencia debe ser texto de una sola línea, de hasta 100 caracteres. |
| `PAYMENT_REFERENCE_HAS_LONG_NUMBER` | 422 | Escribe solo el número de comprobante, sin números de tarjeta ni de cuenta. (Se rechaza cualquier secuencia de 12 o más dígitos seguidos.) |
| `REOPEN_NOT_ALLOWED` | 409 | Este ciclo no se puede reabrir. |

## Agenda, asistencia y confirmación
| Código | HTTP | Mensaje en español |
|---|---|---|
| `EVENT_NOT_FOUND` | 404 | No se encontró la clase. |
| `ATTENDANCE_NOT_FOUND` | 404 | No se encontró la asistencia. |
| `BLOCK_NOT_FOUND` | 404 | No se encontró el bloqueo. |
| `NO_ACTIVE_CYCLE` | 409 | El alumno no tiene un ciclo activo, así que no se puede agendar. |
| `CLASS_IN_PAST` | 422 | No se puede agendar una clase en el pasado. |
| `TOO_SOON` | 422 | Ese horario ya no admite reservas por falta de anticipación. |
| `OUTSIDE_CYCLE` | 422 | La fecha queda fuera de la vigencia del ciclo. |
| `NOT_AVAILABLE` | 422 | Ese horario no está disponible. |
| `BLOCKED` | 422 | Ese horario está bloqueado. |
| `SLOT_TAKEN` | 409 | Ese horario ya está ocupado. |
| `QUOTA_EXCEEDED` | 409 | El alumno ya tiene agendadas todas las clases de su ciclo. |
| `MODALITY_MISMATCH` | 409 | Ese horario es de otra modalidad. |
| `EVENT_FULL` | 409 | La clase ya está llena. |
| `ALREADY_BOOKED` | 409 | El alumno ya está en esa clase. |
| `EVENT_ALREADY_STARTED` | 409 | La clase ya empezó. |
| `CAPACITY_NOT_CONFIGURABLE` | 422 | La capacidad de una clase personalizada no se puede cambiar. |
| `INVALID_CAPACITY` | 422 | La capacidad debe estar entre 2 y 10. |
| `CAPACITY_BELOW_OCCUPANCY` | 409 | La capacidad no puede ser menor que el número de asistentes actuales. |
| `CANCELLATION_WINDOW_CLOSED` | 409 | Ya no se puede cancelar: faltan menos horas de las permitidas. La clase se marcará al terminar. |
| `CLASS_ALREADY_STARTED` | 409 | La clase ya empezó. |
| `CLASS_NOT_STARTED` | 409 | La clase aún no empieza: la asistencia solo se puede confirmar o marcar cuando comienza. |
| `INVALID_STATE` | 409 | La clase no está en un estado que permita esta acción. |
| `ALREADY_MARKED` | 409 | La clase ya estaba marcada así. |
| `CYCLE_CLOSED` | 409 | El ciclo de esta clase ya no está activo. |
| `REASON_REQUIRED` | 422 | Indica el motivo. |
| `DUPLICATE_ATTENDANCE` | 422 | El alumno está repetido en la solicitud. |
| `ATTENDANCE_NOT_IN_EVENT` | 422 | La asistencia no pertenece a esa clase. |
| `INVALID_RANGE` | 422 | El rango de fechas no es válido. |
| `INVALID_AVAILABILITY` | 422 | La disponibilidad enviada no es válida. |
| `OVERLAPPING_AVAILABILITY` | 422 | Hay franjas de disponibilidad que se solapan. |
| `INVALID_BLOCK` | 422 | El bloqueo no es válido. |
| `INVALID_QR` | 400 | El código no es válido o ya venció. Pide al entrenador que muestre el código actual. |
| `QR_NOT_OPEN_YET` | 409 | El código aún no está disponible para esta clase. |
| `QR_WINDOW_CLOSED` | 409 | Ya pasó el tiempo para usar el código de esta clase. |
| `CONFIRMATION_WINDOW_CLOSED` | 409 | Ya pasó el plazo para confirmar esta clase. |

## Perfil del alumno, representante y consentimientos
| Código | HTTP | Mensaje en español |
|---|---|---|
| `INVALID_BIRTH_DATE` | 422 | La fecha de nacimiento es obligatoria y debe ser una fecha real que no sea futura. |
| `GUARDIAN_REQUIRED` | 422 | Un alumno menor de 18 años necesita los datos del representante: nombre, parentesco, celular y correo. |
| `GUARDIAN_INCOMPLETE` | 422 | Los datos del representante van completos o ninguno: nombre, parentesco, celular y correo. |
| `AUDIENCE_CHANGE_BLOCKED` | 409 | No se puede cambiar la fecha de nacimiento de modo que el alumno pase de menor a adulto (o al revés) después de que se aceptó la invitación. |
| `DATA_CONSENT_REQUIRED` | 422 | Debes aceptar la autorización de tratamiento de datos para crear la cuenta. |
| `CONSENT_VERSION_MISMATCH` | 409 | El texto de la autorización cambió. Léelo de nuevo y acéptalo otra vez. |
| `GUARDIAN_CONSENT_NOT_ALLOWED` | 403 | La autorización del representante no se puede registrar desde una sesión de alumno. |
| `CONSENT_ALREADY_ACTIVE` | 409 | Esa autorización ya está vigente. |
| `CONSENT_NOT_ACTIVE` | 409 | No hay una autorización vigente que revocar. |
| `ADULT_CONSENT_NOT_ALLOWED` | 403 | Esta autorización la da el propio alumno mayor de edad desde su cuenta (no el entrenador, ni una cuenta que aún maneja el representante). La de WhatsApp la da el alumno o su representante, no el entrenador. |
| `CONSENT_NOT_APPLICABLE` | 422 | Esa autorización no aplica a este alumno (la de mayor de edad no aplica a un menor, ni la del representante a un adulto). |
| `ANONYMIZATION_PENDING` | 409 | La cuenta está marcada para anonimización: ya no se puede dar una autorización de datos. |

## Forma de los `details` de los errores de renovación (probada en `BulkCancelTest`)

Los textos `explanation` y `description` del servidor están en inglés y son solo para registros: el frontend usa SUS textos en español y decide qué mostrar por `code`.

### `PENDING_SESSIONS_TO_MARK` (409) — se comprueba primero
`details = { pendingSessions: PendingSession[] }`, con `PendingSession = { attendanceId, sessionId, studentId, startsAt, endsAt, eventModality }`. `eventModality` es `PERSONALIZED` o `SEMI_PERSONALIZED`. Salida: marcar esas clases (asistió / no vino) y repetir el pago.

### `MODALITY_CONFLICT_ON_RENEWAL` (409) — solo al renovar el día de la fecha límite
`details = { newPlanModality, conflictingAttendances: FutureAttendance[], explanation, options: Option[] }`, con `FutureAttendance = { attendanceId, sessionId, studentId, startsAt, endsAt, eventModality }` (clases aún sin empezar, en eventos de OTRA modalidad que el plan nuevo) y `Option = { code, description }`. Las salidas, en este orden:
1. `CANCEL_WITHOUT_PENALTY`: cancelar esas clases como entrenador (nadie pierde una clase) con `POST /api/coach/students/{id}/attendances/cancel { attendanceIds, reason }` (todo o nada, una línea `CANCEL` de auditoría por clase) y repetir el pago.
2. `OVERRIDE`: repetir el pago con `overrideModality: true` y `overrideReason` (obligatorio; sin él, `422 OVERRIDE_REASON_REQUIRED`): las clases pasan al ciclo nuevo y quedan marcadas como excepción.

(La salida «marcar primero» pertenece al otro error. No existe «avisar a nadie»: los avisos llegan con la Fase 5.)
