package com.toscana_insights.conversation;

import com.toscana_insights.workspace.CurrentUser;
import com.toscana_insights.workspace.Roles;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/conversations")
@PreAuthorize(Roles.MEMBER)
public class ConversationController {
    private final ConversationService service;
    private final CurrentUser current;

    public ConversationController(ConversationService service, CurrentUser current) { this.service = service; this.current = current; }

    @GetMapping List<ConversationDtos.ConversationResponse> all() { return service.all(); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    ConversationDtos.ConversationResponse create(@Valid @RequestBody ConversationDtos.CreateConversation request) { return service.create(request, current.username()); }

    @GetMapping("/{id}/questions") List<ConversationDtos.QuestionResponse> questions(@PathVariable UUID id) { return service.questions(id); }

    @PostMapping("/{id}/questions") @ResponseStatus(HttpStatus.CREATED)
    ConversationDtos.QuestionResponse ask(@PathVariable UUID id, @Valid @RequestBody ConversationDtos.AskQuestion request) { return service.ask(id, request, current.username()); }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) { service.delete(id, current.username()); }
}
