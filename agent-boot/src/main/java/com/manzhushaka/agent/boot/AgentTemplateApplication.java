package com.manzhushaka.agent.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages = "com.manzhushaka.agent")
public class AgentTemplateApplication { public static void main(String[] args) { SpringApplication.run(AgentTemplateApplication.class, args); } }
