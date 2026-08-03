package com.manzhushaka.agent.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.manzhushaka.agent")
@EnableScheduling
public class AgentTemplateApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentTemplateApplication.class, args);
    }
}
