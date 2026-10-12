package com.toscana_insights.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    List<Conversation> findByWorkspaceIdAndOwnerNameOrderByCreatedAtDesc(UUID workspaceId, String ownerName);
    Optional<Conversation> findByIdAndWorkspaceIdAndOwnerName(UUID id, UUID workspaceId, String ownerName);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.id = :id and c.workspaceId = :workspaceId and c.ownerName = :ownerName")
    Optional<Conversation> lockOwned(UUID id, UUID workspaceId, String ownerName);
    long countByWorkspaceId(UUID workspaceId);
}
