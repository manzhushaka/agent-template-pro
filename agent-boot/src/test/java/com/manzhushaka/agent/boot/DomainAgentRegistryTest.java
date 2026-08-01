package com.manzhushaka.agent.boot;

import com.manzhushaka.agent.runtime.agent.DefaultDomainAgentRegistry;
import com.manzhushaka.agent.spi.action.ActionDescriptor;
import com.manzhushaka.agent.spi.action.ActionMode;
import com.manzhushaka.agent.spi.action.AgentAction;
import com.manzhushaka.agent.spi.context.ActionContext;
import com.manzhushaka.agent.spi.domain.DomainAgentDescriptor;
import com.manzhushaka.agent.spi.domain.DomainModule;
import com.manzhushaka.agent.spi.result.ActionResult;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainAgentRegistryTest {
    @Test
    void rejectsDuplicateAgentCodes() {
        DomainModule first = module("hotel", action("hotel.room.query", ActionMode.QUERY, null));
        DomainModule second = module("hotel", action("hotel.booking.query", ActionMode.QUERY, null));

        assertThrows(IllegalStateException.class, () -> new DefaultDomainAgentRegistry(List.of(first, second)));
    }

    @Test
    void rejectsDuplicateActionsAndWrongOwnershipPrefix() {
        DomainModule hotelWithDuplicate = module(
                "hotel",
                action("hotel.room.query", ActionMode.QUERY, null),
                action("hotel.room.query", ActionMode.QUERY, null)
        );
        DomainModule sportsWithWrongPrefix = module("sports", action("tourism.ticket.query", ActionMode.QUERY, null));

        assertThrows(IllegalStateException.class,
                () -> new DefaultDomainAgentRegistry(List.of(hotelWithDuplicate)));
        assertThrows(IllegalStateException.class,
                () -> new DefaultDomainAgentRegistry(List.of(sportsWithWrongPrefix)));
    }

    @Test
    void rejectsHighRiskActionWithoutConfirmationTitle() {
        DomainModule hotel = module("hotel", action("hotel.booking.create", ActionMode.COMMIT, null));

        assertThrows(IllegalStateException.class, () -> new DefaultDomainAgentRegistry(List.of(hotel)));
    }

    @Test
    void exposesImmutableActionOwnership() {
        DomainModule hotel = module(
                "hotel",
                action("hotel.room.query", ActionMode.QUERY, null),
                action("hotel.booking.create", ActionMode.COMMIT, "确认预订")
        );
        DefaultDomainAgentRegistry registry = new DefaultDomainAgentRegistry(List.of(hotel));

        assertEquals("hotel", registry.findAction("hotel.booking.create").orElseThrow().agentCode());
        assertThrows(UnsupportedOperationException.class,
                () -> registry.findAgent("hotel").orElseThrow().actions().clear());
    }

    private DomainModule module(String code, AgentAction... actions) {
        return new DomainModule() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public String displayName() {
                return code;
            }

            @Override
            public Collection<AgentAction> actions() {
                return List.of(actions);
            }

            @Override
            public DomainAgentDescriptor agentDescriptor() {
                return new DomainAgentDescriptor(
                        code, code, code, "bot", code, List.of(code), List.of(), 1, true
                );
            }
        };
    }

    private AgentAction action(String code, ActionMode mode, String confirmationTitle) {
        return new AgentAction() {
            @Override
            public ActionDescriptor descriptor() {
                return new ActionDescriptor(code, code, mode, List.of(), confirmationTitle);
            }

            @Override
            public ActionResult execute(ActionContext context, Map<String, Object> input) {
                return ActionResult.card("ok", "test", Map.of());
            }
        };
    }
}
