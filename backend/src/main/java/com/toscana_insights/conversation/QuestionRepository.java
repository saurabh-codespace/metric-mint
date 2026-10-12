package com.toscana_insights.conversation;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRepository extends JpaRepository<Question, UUID> {
    List<Question> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
    long countByConversationId(UUID conversationId);
    List<Question> findByStatusAndCreatedAtBefore(String status, Instant before);
}
