package com.productsearch.infra;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SessionManager {

    private final Map<String, ChatMemory> sessions = new ConcurrentHashMap<>();

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public void addUserMessage(String sessionId, String message) {
        sessions.computeIfAbsent(sessionId, id -> MessageWindowChatMemory.withMaxMessages(10))
                .add(UserMessage.from(message));
    }

    public String getCombinedUserQuery(String sessionId) {
        ChatMemory memory = sessions.get(sessionId);
        if (memory == null) return null;
        return memory.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(msg -> ((UserMessage) msg).singleText())
                .filter(t -> t != null && !t.isEmpty())
                .collect(Collectors.joining(". "));
    }
}
