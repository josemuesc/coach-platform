package com.coachplatform.billing;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByStudentIdOrderByPaidOnDescCreatedAtDesc(UUID studentId);
}
