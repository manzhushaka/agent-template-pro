package com.manzhushaka.agent.spi.action;

import com.manzhushaka.agent.spi.context.ActionContext;
import com.manzhushaka.agent.spi.result.ActionResult;
import java.util.Map;

public interface AgentAction {
    ActionDescriptor descriptor();
    ActionResult execute(ActionContext context, Map<String, Object> input);
}
