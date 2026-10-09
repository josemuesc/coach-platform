# Corregir un pago mal registrado (procedimiento manual)

Un pago es evidencia contable e **inmutable**: desde la V8 un trigger (`trg_payment_immutable`, `trg_payment_no_truncate`) impide `UPDATE`, `DELETE` y `TRUNCATE`, y el rol de la aplicación (`app_runtime`) ni siquiera tiene el permiso. La aplicación no puede corregir un pago; **un flujo de anulación dentro de la app queda para después del piloto** (ver CLAUDE.md). Mientras tanto, la corrección es manual y excepcional.

## Qué se puede corregir con este procedimiento
Solo los datos del pago que NO cambian el ciclo: **`amount_cop`**, **`method`** y **`reference`**.

No se corrige aquí un pago hecho al alumno equivocado, con el plan equivocado o con la fecha equivocada: eso cambia el ciclo (fechas, clases incluidas, clases ya descontadas o agendadas) y se trata caso por caso, con el abogado/entrenador, sin tocar `payment.paid_on` ni `cycle_id` a la ligera. Si ocurre, parar y consultar al responsable del producto.

## Quién lo hace y con qué autorización
- **Quién**: únicamente el operador de la plataforma, con la cuenta **dueña de las tablas / de las migraciones** (en Supabase, `postgres`, desde el editor SQL o `psql`). Nunca desde la aplicación ni con `app_runtime`.
- **Autorización**: el entrenador dueño del pago lo pide por escrito (WhatsApp o correo) indicando el pago, el dato mal registrado y el valor correcto. El operador confirma por otra vía (llamada) que el pedido es del entrenador. Sin eso, no se toca.

## Cómo se hace (una sola transacción, con ensayo previo)
Se identifica el pago por su `id` (`GET /api/coach/payments?studentId=…` lo muestra) y se prepara el cambio. **Primero se ensaya terminando en `ROLLBACK`; cuando el resultado es el esperado, se repite con `COMMIT`.**

```sql
BEGIN;

-- 1. Foto de «antes» (guardarla en la constancia)
SELECT id, coach_id, student_id, cycle_id, amount_cop, method, paid_on, reference, recorded_by, created_at
FROM payment WHERE id = '<payment-id>' FOR UPDATE;

-- 2. Quitar la protección SOLO dentro de esta transacción. DISABLE TRIGGER pide ser dueño de la tabla y toma un candado
--    exclusivo breve; si algo falla y se hace ROLLBACK, el trigger vuelve solo a su estado anterior.
ALTER TABLE payment DISABLE TRIGGER trg_payment_immutable;

-- 3. UNA sentencia, con el id (nunca por alumno ni por fecha). Cambiar solo lo que se corrige.
UPDATE payment SET amount_cop = <monto>, method = '<NEQUI|TRANSFER|CASH|OTHER>', reference = <'texto' | NULL>
WHERE id = '<payment-id>';
-- debe decir UPDATE 1; si dice otra cifra: ROLLBACK y parar

-- 4. Volver a poner la protección y comprobarlo ANTES de confirmar
ALTER TABLE payment ENABLE TRIGGER trg_payment_immutable;
SELECT tgname, tgenabled FROM pg_trigger WHERE tgrelid = 'payment'::regclass AND NOT tgisinternal;
-- trg_payment_immutable y trg_payment_no_truncate deben salir con tgenabled = 'O'

-- 5. Foto de «después» (la misma consulta del paso 1)

COMMIT;   -- (en el ensayo: ROLLBACK)
```

Notas:
- Las restricciones de la tabla siguen aplicando (`ck_payment_method`, `ck_payment_reference`: sin saltos de línea, sin 12 o más dígitos seguidos, sin espacios en los bordes): el `UPDATE` falla si el valor nuevo no las cumple.
- No usar `TRUNCATE`, `DELETE` ni `session_replication_role` para «arreglar» pagos. Un pago de más no se borra: se consulta primero (es un caso de ciclo, ver arriba).
- Si se corrige el **monto o el método de un pago de un ciclo ya cerrado**, el ciclo no cambia: solo cambia el dato contable.

## Cómo se deja constancia
No existe (todavía) una tabla de auditoría de correcciones; la constancia es un **registro del operador**, guardado fuera de la aplicación (carpeta privada de operaciones, no en el repositorio), con una entrada por corrección:

| Campo | Contenido |
| --- | --- |
| Fecha y hora (Bogotá) | cuándo se aplicó |
| Operador | quién |
| Pedido | quién lo pidió, por qué canal, fecha, y cómo se confirmó |
| Pago | `id`, entrenador y alumno |
| Antes / después | las dos fotos de los pasos 1 y 5 |
| Sentencia | el `UPDATE` exacto que se ejecutó |
| Verificación | el resultado de las comprobaciones de abajo |

Además, se le avisa al entrenador por el mismo canal del pedido, con el valor anterior y el nuevo.

## Cómo se re-verifica el ciclo
Aunque el ciclo no cambia, se comprueba que todo sigue coherente (todas deben dar el resultado indicado):

```sql
-- el pago sigue ligado a su ciclo y a su alumno, y su fecha es el inicio del ciclo
SELECT p.id, p.paid_on = c.start_date AS fecha_ok, p.student_id = c.student_id AS alumno_ok
FROM payment p JOIN cycle c ON c.id = p.cycle_id AND c.coach_id = p.coach_id WHERE p.id = '<payment-id>';
--> fecha_ok = true, alumno_ok = true

-- un solo ciclo activo por alumno
SELECT count(*) FROM cycle WHERE student_id = '<student-id>' AND status = 'ACTIVE';
--> 0 o 1

-- clases usadas = clases marcadas (asistió / no vino) de ese ciclo
SELECT c.classes_used,
       (SELECT count(*) FROM session_attendance a
         WHERE a.cycle_id = c.id AND a.status IN ('ATTENDED', 'NO_SHOW')) AS marcadas
FROM cycle c WHERE c.id = '<cycle-id>';
--> las dos cifras iguales
```

Por último, el entrenador abre el alumno en la aplicación (o `GET /api/coach/billing/overview`) y confirma que el ciclo, las clases restantes y la fecha límite no cambiaron.
