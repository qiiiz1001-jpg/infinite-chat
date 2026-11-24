package cn.qingliao.aiservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.qingliao.aiservice.client.AgentActionClient;
import cn.qingliao.aiservice.tool.ImActionTool;
import cn.qingliao.common.common.BaseResponse;
import cn.qingliao.common.model.dto.AgentActions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.Tool;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

class ImActionToolTest {
    private Action draft() {
        Action a = new Action();
        a.setActionId(77L);
        a.setStatus("DRAFT");
        return a;
    }

    @Test
    void toolSchedulesPersonalReminderWithBoundIdentityAndStableRequestKey() {
        AgentActionClient client = mock(AgentActionClient.class);
        var reminder = draft();
        reminder.setKind("REMINDER");
        reminder.setStatus("PENDING");
        when(client.scheduleReminder(eq(1001L), any()))
                .thenReturn(new BaseResponse<>(200, reminder));
        ImActionTool tool = new ImActionTool(1001, 20, client, new ObjectMapper());
        String first = tool.preparePersonalReminder("交简历", "2030-01-01T15:00:00+08:00");
        tool.preparePersonalReminder("交简历", "2030-01-01T15:00:00+08:00");
        var args = org.mockito.ArgumentCaptor.forClass(Draft.class);
        verify(client, times(2)).scheduleReminder(eq(1001L), args.capture());
        assertEquals(20L, args.getValue().sessionId());
        assertEquals("REMINDER", args.getValue().kind());
        assertEquals(
                args.getAllValues().get(0).requestKey(), args.getAllValues().get(1).requestKey());
        assertTrue(first.contains("个人提醒已排期"));
        assertTrue(first.contains("notice"));
        assertThrows(RuntimeException.class, () -> tool.preparePersonalReminder("x", "明天下午"));
    }

    @Test
    void identitySurvivesThreadSwitchAndDoesNotLeakBetweenUsers() throws Exception {
        AgentActionClient client = mock(AgentActionClient.class);
        when(client.draft(anyLong(), any())).thenReturn(new BaseResponse<>(200, draft()));
        ImActionTool alice = new ImActionTool(1001, 20, client, new ObjectMapper());
        ImActionTool bob = new ImActionTool(2002, 30, client, new ObjectMapper());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> alice.prepareSessionMessage("10", "Alice", null))
                    .get(3, TimeUnit.SECONDS);
            pool.submit(() -> bob.prepareSessionMessage("11", "Bob", null))
                    .get(3, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        verify(client)
                .draft(eq(1001L), argThat(d -> d.sessionId() == 10 && d.content().equals("Alice")));
        verify(client)
                .draft(eq(2002L), argThat(d -> d.sessionId() == 11 && d.content().equals("Bob")));
    }

    @Test
    void schemaHasNoConfirmationOrIdentityParametersAndErrorsAreNotReportedAsSuccess() {
        for (var method : ImActionTool.class.getDeclaredMethods())
            if (method.isAnnotationPresent(Tool.class)) {
                assertFalse(method.getName().toLowerCase().contains("confirm"));
                for (var param : method.getParameters())
                    assertFalse(Set.of("actorId", "userId", "ownerId").contains(param.getName()));
            }
        AgentActionClient client = mock(AgentActionClient.class);
        when(client.list(1001)).thenReturn(new BaseResponse<>(40300, null, "无权访问"));
        assertTrue(
                new ImActionTool(1001, 20, client, new ObjectMapper())
                        .listMyActions()
                        .contains("操作未完成"));
    }
}
