package com.vllm.gateway.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.vllm.gateway.entity.ChatMessage;
import com.vllm.gateway.entity.ChatSession;
import com.vllm.gateway.entity.RequestLog;
import com.vllm.gateway.repository.ChatMessageMapper;
import com.vllm.gateway.repository.ChatSessionMapper;
import com.vllm.gateway.repository.RequestLogMapper;
import com.vllm.gateway.util.TimeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class CleanupTask {

    @Value("${log.keep-days:90}")
    private int keepDays;

    @Autowired
    private RequestLogMapper requestLogMapper;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanup() {
        String threshold = TimeUtil.format(LocalDateTime.now().minusDays(keepDays));

        requestLogMapper.delete(new QueryWrapper<RequestLog>().lt("request_time", threshold));

        List<ChatSession> expiredSessions = chatSessionMapper.selectList(
                new QueryWrapper<ChatSession>().select("id").lt("update_time", threshold));
        if (!expiredSessions.isEmpty()) {
            List<Long> ids = expiredSessions.stream().map(ChatSession::getId).toList();
            chatMessageMapper.delete(new QueryWrapper<ChatMessage>().in("session_id", ids));
            chatSessionMapper.delete(new QueryWrapper<ChatSession>().in("id", ids));
        }
    }
}
