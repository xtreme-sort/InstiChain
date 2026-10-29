package edu.iitm.instichain.domain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class CredentialRevocationRequestTest {
    @Test
    void reasonIsRequiredAndBounded() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new CredentialRevocationController.Draft("Incorrect award")).isEmpty());
            assertTrue(validator.validate(new CredentialRevocationController.Draft(" ")).stream()
                    .anyMatch(violation -> violation.getPropertyPath().toString().equals("reason")));
            assertTrue(validator.validate(new CredentialRevocationController.Draft("x".repeat(501))).stream()
                    .anyMatch(violation -> violation.getPropertyPath().toString().equals("reason")));
        }
    }
}
