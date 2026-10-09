package com.coachplatform.billing;

import com.coachplatform.billing.api.PaymentMethod;
import com.coachplatform.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "payment")
class Payment extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Column(name = "cycle_id", nullable = false, updatable = false)
    private UUID cycleId;

    @Column(name = "amount_cop", nullable = false, updatable = false)
    private long amountCop;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentMethod method;

    @Column(name = "paid_on", nullable = false, updatable = false)
    private LocalDate paidOn;

    @Column(length = 100, updatable = false)
    private String reference;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Payment() {
    }

    Payment(UUID studentId, UUID cycleId, long amountCop, PaymentMethod method, LocalDate paidOn, UUID recordedBy,
            String reference) {
        this.studentId = studentId;
        this.cycleId = cycleId;
        this.amountCop = amountCop;
        this.method = method;
        this.paidOn = paidOn;
        this.recordedBy = recordedBy;
        this.reference = reference;
    }

    UUID getId() { return id; }
    UUID getStudentId() { return studentId; }
    UUID getCycleId() { return cycleId; }
    long getAmountCop() { return amountCop; }
    PaymentMethod getMethod() { return method; }
    LocalDate getPaidOn() { return paidOn; }
    String getReference() { return reference; }
    UUID getRecordedBy() { return recordedBy; }
    Instant getCreatedAt() { return createdAt; }
}
