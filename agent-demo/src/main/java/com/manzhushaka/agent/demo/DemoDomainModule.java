package com.manzhushaka.agent.demo;

import com.manzhushaka.agent.spi.action.ActionDescriptor;
import com.manzhushaka.agent.spi.action.ActionMode;
import com.manzhushaka.agent.spi.action.AgentAction;
import com.manzhushaka.agent.spi.context.ActionContext;
import com.manzhushaka.agent.spi.domain.DomainAgentDescriptor;
import com.manzhushaka.agent.spi.domain.DomainModule;
import com.manzhushaka.agent.spi.domain.SuggestedPromptDescriptor;
import com.manzhushaka.agent.spi.result.ActionResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Configuration
public class DemoDomainModule {
    @Bean
    DomainModule hotelModule() {
        return module(
                descriptor(
                        "hotel", "酒店服务", "房态、报价、预订和订单服务", "hotel",
                        "处理酒店、房间、入住、退房、住宿和酒店会员需求",
                        List.of("酒店", "房间", "房型", "入住", "住宿", "海景房"),
                        List.of(
                                prompt("查询房态", "明天上海还有海景房吗？"),
                                prompt("预订酒店", "帮张三预订明天的酒店")
                        ),
                        10
                ),
                List.of(
                        action("hotel.room.search", "查询酒店房态", ActionMode.QUERY, List.of("city", "date"), null,
                                (context, input) -> ActionResult.card(
                                        input.get("city") + "有 3 个可预订房型。", "hotel-room",
                                        Map.of("city", input.get("city"), "date", input.get("date"),
                                                "roomType", "海景大床房", "price", "688元起")
                                )),
                        action("hotel.booking.query", "查询酒店预订", ActionMode.QUERY, List.of("bookingNo"), null,
                                (context, input) -> ActionResult.card(
                                        "已找到酒店预订。", "hotel-booking",
                                        Map.of("bookingNo", input.get("bookingNo"), "status", "待入住")
                                )),
                        action("hotel.booking.create", "创建酒店预订", ActionMode.COMMIT,
                                List.of("guestName", "date"), "确认提交酒店预订",
                                (context, input) -> ActionResult.card(
                                        "酒店预订已受理。", "hotel-booking",
                                        Map.of("guestName", input.get("guestName"), "date", input.get("date"),
                                                "reference", reference("HTL", context))
                                ))
                )
        );
    }

    @Bean
    DomainModule sportsModule() {
        return module(
                descriptor(
                        "sports", "体育服务", "场馆、赛事、活动、预约和票务服务", "activity",
                        "处理体育、比赛、运动、场馆、场地、赛事和体育票务需求",
                        List.of("体育", "比赛", "运动", "场馆", "场地", "赛事", "球赛"),
                        List.of(
                                prompt("查询赛事", "周末体育馆有哪些比赛？"),
                                prompt("预约场馆", "帮我预约明天的城市体育馆")
                        ),
                        20
                ),
                List.of(
                        action("sports.event.query", "查询体育赛事", ActionMode.QUERY, List.of("date"), null,
                                (context, input) -> ActionResult.card(
                                        "已找到 2 场公开体育活动。", "sports-event",
                                        Map.of("date", input.get("date"), "event", "城市篮球邀请赛", "venue", "城市体育馆")
                                )),
                        action("sports.ticket.query", "查询体育票务", ActionMode.QUERY, List.of("ticketNo"), null,
                                (context, input) -> ActionResult.card(
                                        "票务状态正常。", "sports-ticket",
                                        Map.of("ticketNo", input.get("ticketNo"), "status", "待核销")
                                )),
                        action("sports.venue.reserve", "预约体育场馆", ActionMode.COMMIT,
                                List.of("venue", "date"), "确认预约体育场馆",
                                (context, input) -> ActionResult.card(
                                        "场馆预约已受理。", "sports-reservation",
                                        Map.of("venue", input.get("venue"), "date", input.get("date"),
                                                "reference", reference("SPT", context))
                                ))
                )
        );
    }

    @Bean
    DomainModule tourismModule() {
        return module(
                descriptor(
                        "tourism", "文旅服务", "景区、路线、门票和现场活动服务", "landmark",
                        "处理景区、旅游、文旅、路线、游玩、门票和现场活动需求",
                        List.of("景区", "旅游", "文旅", "路线", "游玩", "景点", "老人"),
                        List.of(
                                prompt("路线推荐", "带老人去哪个景区比较合适？"),
                                prompt("购买门票", "购买明天海洋公园门票")
                        ),
                        30
                ),
                List.of(
                        action("tourism.route.recommend", "推荐文旅路线", ActionMode.QUERY, List.of("city"), null,
                                (context, input) -> ActionResult.card(
                                        "为你推荐一条节奏舒缓的城市文旅路线。", "tourism-route",
                                        Map.of("city", input.get("city"), "route", "博物馆 - 湖畔步道 - 非遗街区", "duration", "6小时")
                                )),
                        action("tourism.ticket.query", "查询景区门票", ActionMode.QUERY, List.of("ticketNo"), null,
                                (context, input) -> ActionResult.card(
                                        "景区门票有效。", "tourism-ticket",
                                        Map.of("ticketNo", input.get("ticketNo"), "status", "待使用")
                                )),
                        action("tourism.ticket.create", "购买景区门票", ActionMode.COMMIT,
                                List.of("attraction", "date"), "确认购买景区门票",
                                (context, input) -> ActionResult.card(
                                        "景区门票订单已受理。", "tourism-ticket",
                                        Map.of("attraction", input.get("attraction"), "date", input.get("date"),
                                                "reference", reference("TRV", context))
                                ))
                )
        );
    }

    @Bean
    DomainModule dutyFreeModule() {
        return module(
                descriptor(
                        "dutyfree", "免税服务", "商品、价格、资格、提货和售后服务", "shopping-bag",
                        "处理免税、离岛、商品、香化、购买资格、提货和免税售后需求",
                        List.of("免税", "离岛", "提货", "香水", "美妆", "免税店"),
                        List.of(
                                prompt("查询商品", "离岛免税的香水有哪些？"),
                                prompt("免税下单", "购买两件免税香水")
                        ),
                        40
                ),
                List.of(
                        action("dutyfree.product.query", "查询免税商品", ActionMode.QUERY, List.of("keyword"), null,
                                (context, input) -> ActionResult.card(
                                        "已找到 4 件可售免税商品。", "dutyfree-product",
                                        Map.of("keyword", input.get("keyword"), "product", "海风淡香水 50ml", "price", "459元")
                                )),
                        action("dutyfree.pickup.query", "查询免税提货", ActionMode.QUERY, List.of("pickupNo"), null,
                                (context, input) -> ActionResult.card(
                                        "商品已到达提货点。", "dutyfree-pickup",
                                        Map.of("pickupNo", input.get("pickupNo"), "status", "可提货")
                                )),
                        action("dutyfree.order.create", "创建免税订单", ActionMode.COMMIT,
                                List.of("productName", "quantity"), "确认提交免税订单",
                                (context, input) -> ActionResult.async(
                                        "免税订单已提交资格校验。", "dutyfree-order",
                                        Map.of("productName", input.get("productName"), "quantity", input.get("quantity"),
                                                "reference", reference("DTF", context), "status", "资格校验中")
                                ))
                )
        );
    }

    private DomainModule module(DomainAgentDescriptor descriptor, Collection<AgentAction> actions) {
        return new DomainModule() {
            @Override
            public String code() {
                return descriptor.code();
            }

            @Override
            public String displayName() {
                return descriptor.displayName();
            }

            @Override
            public Collection<AgentAction> actions() {
                return actions;
            }

            @Override
            public DomainAgentDescriptor agentDescriptor() {
                return descriptor;
            }
        };
    }

    private DomainAgentDescriptor descriptor(
            String code,
            String displayName,
            String description,
            String iconKey,
            String routingDescription,
            List<String> routingHints,
            List<SuggestedPromptDescriptor> prompts,
            int order
    ) {
        return new DomainAgentDescriptor(
                code, displayName, description, iconKey, routingDescription,
                routingHints, prompts, order, true
        );
    }

    private SuggestedPromptDescriptor prompt(String title, String prompt) {
        return new SuggestedPromptDescriptor(title, prompt);
    }

    private AgentAction action(
            String code,
            String name,
            ActionMode mode,
            List<String> requiredFields,
            String confirmationTitle,
            Executor executor
    ) {
        return new SimpleAction(
                new ActionDescriptor(code, name, mode, requiredFields, confirmationTitle),
                executor
        );
    }

    private String reference(String prefix, ActionContext context) {
        return prefix + '-' + context.requestId().substring(0, Math.min(8, context.requestId().length())).toUpperCase();
    }

    private record SimpleAction(ActionDescriptor descriptor, Executor executor) implements AgentAction {
        @Override
        public ActionResult execute(ActionContext context, Map<String, Object> input) {
            return executor.run(context, input);
        }
    }

    private interface Executor {
        ActionResult run(ActionContext context, Map<String, Object> input);
    }
}
