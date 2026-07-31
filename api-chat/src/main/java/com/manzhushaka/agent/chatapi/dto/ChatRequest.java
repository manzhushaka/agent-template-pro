package com.manzhushaka.agent.chatapi.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record ChatRequest(@NotBlank @Size(max = 2000) String content) { }
