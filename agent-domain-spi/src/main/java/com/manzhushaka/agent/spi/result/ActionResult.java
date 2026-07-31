package com.manzhushaka.agent.spi.result;

import java.util.Map;
public record ActionResult(String summary, String cardType, Map<String, Object> cardData, boolean waitingExternalResult) {
    public static ActionResult card(String summary, String type, Map<String, Object> data) { return new ActionResult(summary, type, data, false); }
    public static ActionResult async(String summary, String type, Map<String, Object> data) { return new ActionResult(summary, type, data, true); }
}
