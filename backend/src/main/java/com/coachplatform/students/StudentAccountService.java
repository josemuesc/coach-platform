package com.coachplatform.students;

import com.coachplatform.auth.AuthService;
import com.coachplatform.auth.api.PasswordInfo;
import com.coachplatform.students.api.StudentAccountView;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only: the state of a student's login as the coach sees it on the profile. */
@Service
public class StudentAccountService {

    private final StudentRepository students;
    private final PasswordResetRepository resets;
    private final AuthService auth;
    private final Clock clock;

    StudentAccountService(StudentRepository students, PasswordResetRepository resets, AuthService auth, Clock clock) {
        this.students = students;
        this.resets = resets;
        this.auth = auth;
        this.clock = clock;
    }

    /** 404 for a student of another tenant (the repository is tenant-filtered). */
    @Transactional(readOnly = true)
    public StudentAccountView of(UUID studentId) {
        Student student = students.findById(studentId).orElseThrow(StudentNotFoundException::new);
        if (!student.hasAccount()) {
            return new StudentAccountView(false, student.isActive(), null, null, null);
        }
        PasswordInfo password = auth.passwordInfo(student.getUserId());
        Instant now = clock.instant();
        Instant openUntil = resets.findOpenByUserId(student.getUserId()).stream()
                .map(PasswordReset::getExpiresAt).filter(at -> at.isAfter(now)).findFirst().orElse(null);
        return new StudentAccountView(true, student.isActive(), password.changedAt(), password.method(), openUntil);
    }
}
