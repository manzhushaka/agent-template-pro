package com.manzhushaka.agent.runtime.task;

import java.time.Instant;
import java.util.Map;

public final class AgentTask {
    private final String id, visitorId, conversationId, actionCode, idempotencyKey;
    private final Map<String,Object> input;
    private final Instant createdAt;
    private TaskStatus status;
    private int confirmationVersion;
    private String externalRef;
    public AgentTask(String id, String visitorId, String conversationId, String actionCode, String idempotencyKey, Map<String,Object> input) { this.id=id; this.visitorId=visitorId; this.conversationId=conversationId; this.actionCode=actionCode; this.idempotencyKey=idempotencyKey; this.input=Map.copyOf(input); this.createdAt=Instant.now(); this.status=TaskStatus.CREATED; }
    public String id(){return id;} public String visitorId(){return visitorId;} public String conversationId(){return conversationId;} public String actionCode(){return actionCode;} public String idempotencyKey(){return idempotencyKey;} public Map<String,Object> input(){return input;} public Instant createdAt(){return createdAt;} public TaskStatus status(){return status;} public int confirmationVersion(){return confirmationVersion;} public String externalRef(){return externalRef;}
    public void status(TaskStatus value){status=value;} public void confirmationVersion(int value){confirmationVersion=value;} public void externalRef(String value){externalRef=value;}
}
