package com.coachplatform.students;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StudentRepository extends JpaRepository<Student, UUID> {

    boolean existsByEmail(String email);

    Optional<Student> findByEmail(String email);

    List<Student> findAllByOrderByFullNameAsc();

    /** SELECT ... FOR UPDATE, tenant-filtered like every other query. Serializes work on one student. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Student s where s.id = :id")
    Optional<Student> findByIdForUpdate(@Param("id") UUID id);
}
