package com.coachplatform.students;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ConsentRevocationRepository extends JpaRepository<ConsentRevocation, UUID> {

    List<ConsentRevocation> findByStudentId(UUID studentId);
}
