package edu.iitm.instichain.domain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResultRequestValidationTest {
    @Test
    void requiresARealSha256DigestAndRecipients() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new ResultController.SubmissionRequest(
                    "First place", "a".repeat(64), List.of("student@smail.iitm.ac.in"))).isEmpty());
            assertTrue(validator.validate(new ResultController.SubmissionRequest(
                    "First place", null, List.of("student@smail.iitm.ac.in"))).stream()
                    .anyMatch(violation -> violation.getPropertyPath().toString().equals("proofDigest")));
            assertTrue(validator.validate(new ResultController.SubmissionRequest(
                    "First place", "not-a-digest", List.of("student@smail.iitm.ac.in"))).stream()
                    .anyMatch(violation -> violation.getPropertyPath().toString().equals("proofDigest")));
        }
    }
}
