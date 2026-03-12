package com.decisionmesh.contracts.security.resource.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class SignupRequest {

    @NotBlank
    @Email
    public String email;

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 characters")
    public String password;

    @NotBlank
    public String organizationName;
    @NotBlank
    public String idempotencyKey;

    // Optional: for onboarding / profile
    public String firstName;
    public String lastName;
}
