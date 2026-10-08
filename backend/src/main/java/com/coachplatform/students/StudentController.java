package com.coachplatform.students;

import com.coachplatform.security.AuthPrincipal;
import com.coachplatform.students.api.InvitationIssued;
import com.coachplatform.students.api.StudentInput;
import com.coachplatform.students.api.StudentSummary;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coach/students")
class StudentController {

    private final StudentService students;
    private final String frontendUrl;

    StudentController(StudentService students, @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.students = students;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    record StudentWithInvite(StudentSummary student, String inviteUrl, Instant inviteExpiresAt) {
        @Override
        public String toString() {
            return "StudentWithInvite[student=" + student + ", inviteUrl=<redacted>]";
        }
    }

    record InviteResponse(String inviteUrl, Instant expiresAt) {
        @Override
        public String toString() {
            return "InviteResponse[expiresAt=" + expiresAt + ", inviteUrl=<redacted>]";
        }
    }

    record UpdateStudentRequest(@Valid StudentInput data, Boolean active) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    StudentWithInvite create(@AuthenticationPrincipal AuthPrincipal me, @Valid @RequestBody StudentInput input) {
        var created = students.create(input, me.userId());
        return new StudentWithInvite(created.student(), link(created.invitation()), created.invitation().expiresAt());
    }

    @GetMapping
    List<StudentSummary> list() {
        return students.list();
    }

    @GetMapping("/{id}")
    StudentSummary get(@PathVariable UUID id) {
        return students.get(id);
    }

    @PutMapping("/{id}")
    StudentSummary update(@PathVariable UUID id, @Valid @RequestBody UpdateStudentRequest req) {
        return students.update(id, req.data(), req.active());
    }

    @PostMapping("/{id}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InviteResponse reissue(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        InvitationIssued issued = students.reissueInvitation(id, me.userId());
        return new InviteResponse(link(issued), issued.expiresAt());
    }

    private String link(InvitationIssued issued) {
        return frontendUrl + "/invite/" + issued.token();
    }
}
