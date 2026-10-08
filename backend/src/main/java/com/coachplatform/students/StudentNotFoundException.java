package com.coachplatform.students;

import com.coachplatform.common.ApiException;
import org.springframework.http.HttpStatus;

public class StudentNotFoundException extends ApiException {

    public StudentNotFoundException() {
        super(HttpStatus.NOT_FOUND, "STUDENT_NOT_FOUND");
    }
}
