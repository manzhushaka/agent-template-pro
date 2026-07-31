package com.manzhushaka.agent.chatapi.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
public record ConfirmRequest(@Positive int confirmationVersion, @NotBlank String decision) { }
