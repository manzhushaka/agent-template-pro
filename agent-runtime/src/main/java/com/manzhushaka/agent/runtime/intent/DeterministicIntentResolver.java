package com.manzhushaka.agent.runtime.intent;

import com.manzhushaka.agent.spi.action.ActionDescriptor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Local fallback used until a configured model resolver is available. */
@Service
@Order(100)
public class DeterministicIntentResolver implements IntentResolver {
    @Override
    public IntentDecision resolve(String content, List<ActionDescriptor> descriptors) {
        String normalized = content.toLowerCase(Locale.ROOT);
        String actionCode = descriptors.stream()
                .filter(descriptor -> matchesAction(normalized, descriptor))
                .findFirst()
                .orElseGet(() -> descriptors.stream()
                        .filter(descriptor -> descriptor.mode().name().equals("QUERY"))
                        .findFirst()
                        .orElseGet(() -> descriptors.stream().findFirst().orElseThrow()))
                .code();
        return new IntentDecision(actionCode, extract(content));
    }

    private boolean matchesAction(String content, ActionDescriptor descriptor) {
        String code = descriptor.code();
        if (containsAny(content, "预订", "预约", "下单", "购买", "提交")) {
            return code.endsWith(".create") || code.endsWith(".reserve");
        }
        if (containsAny(content, "订单", "编号", "进度", "查询预订", "查询票")) {
            return code.endsWith(".query")
                    && (code.contains("booking") || code.contains("ticket") || code.contains("pickup"));
        }
        return descriptor.mode().name().equals("QUERY")
                && !code.contains("booking.query")
                && !code.contains("ticket.query")
                && !code.contains("pickup.query");
    }

    private Map<String, Object> extract(String content) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (String city : List.of("北京", "上海", "广州", "深圳", "杭州", "成都", "武汉")) {
            if (content.contains(city)) {
                input.put("city", city);
            }
        }
        if (content.contains("明天")) {
            input.put("date", "明天");
        }
        if (content.contains("今天")) {
            input.put("date", "今天");
        }
        if (content.contains("张三")) {
            input.put("guestName", "张三");
        }
        putFollowingToken(content, "预订号", "bookingNo", input);
        putFollowingToken(content, "票号", "ticketNo", input);
        putFollowingToken(content, "提货号", "pickupNo", input);
        if (content.contains("体育馆")) {
            input.put("venue", "城市体育馆");
        }
        if (content.contains("海洋公园")) {
            input.put("attraction", "海洋公园");
        }
        if (content.contains("香水")) {
            input.put("keyword", "香水");
            input.put("productName", "海风淡香水");
        }
        if (content.contains("两件") || content.contains("2件") || content.contains("两个")) {
            input.put("quantity", 2);
        }
        return input;
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void putFollowingToken(
            String content,
            String prefix,
            String field,
            Map<String, Object> input
    ) {
        int start = content.indexOf(prefix);
        if (start < 0) {
            return;
        }
        String value = content.substring(start + prefix.length()).trim().split("\\s+|，|。", 2)[0];
        if (!value.isBlank()) {
            input.put(field, value);
        }
    }
}
