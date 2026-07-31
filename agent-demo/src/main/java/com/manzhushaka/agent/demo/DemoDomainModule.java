package com.manzhushaka.agent.demo;

import com.manzhushaka.agent.spi.action.*;
import com.manzhushaka.agent.spi.context.ActionContext;
import com.manzhushaka.agent.spi.domain.DomainModule;
import com.manzhushaka.agent.spi.result.ActionResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.*;

@Configuration
public class DemoDomainModule {
    @Bean DomainModule demoModule() { return new DomainModule() {
        public String code() { return "demo"; } public String displayName() { return "公开示例"; }
        public Collection<AgentAction> actions() { return List.of(weather(), appointment(), delivery()); }
    }; }
    private AgentAction weather() { return new SimpleAction(new ActionDescriptor("demo.weather.query", "查询城市天气", ActionMode.QUERY, List.of("city"), null), (context, input) -> { String city=String.valueOf(input.get("city")); return ActionResult.card(city + "当前晴朗，适合出行。", "weather", Map.of("city", city, "temperature", "26C", "condition", "晴朗", "updatedAt", "刚刚")); }); }
    private AgentAction appointment() { return new SimpleAction(new ActionDescriptor("demo.appointment.create", "创建体验预约", ActionMode.COMMIT, List.of("name", "date"), "确认提交预约"), (context, input) -> ActionResult.card("预约已受理，我们会在约定日期为您准备服务。", "appointment", Map.of("name", input.get("name"), "date", input.get("date"), "reference", "DEMO-" + context.requestId().substring(0, Math.min(8, context.requestId().length()))))); }
    private AgentAction delivery() { return new SimpleAction(new ActionDescriptor("demo.delivery.track", "查询配送进度", ActionMode.COMMIT, List.of("trackingNo"), "确认查询配送进度"), (context, input) -> ActionResult.async("已提交配送状态查询，结果会在任务卡片中更新。", "delivery", Map.of("trackingNo", input.get("trackingNo"), "state", "查询中"))); }
    private record SimpleAction(ActionDescriptor descriptor, Executor executor) implements AgentAction { public ActionResult execute(ActionContext context, Map<String,Object> input) { return executor.run(context, input); } }
    private interface Executor { ActionResult run(ActionContext context, Map<String,Object> input); }
}
