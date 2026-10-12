package com.toscana_insights.conversation;

import com.toscana_insights.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "app_questions", schema = "toscana_insights")
public class Question extends BaseEntity {
    @Column(nullable = false) private UUID conversationId;
    @Column(nullable = false, length = 4000) private String questionText;
    @Column(nullable = false, columnDefinition = "nvarchar(max)") private String answerText;
    @Column(nullable = false, length = 30) private String visualization;
    @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, columnDefinition = "nvarchar(max)") private String resultJson;
    @Column(nullable = false, length = 12) private String status = "pending";
    @Column(length = 500) private String error;

    protected Question() {}
    public Question(UUID conversationId, String questionText, String resultJson) {
        this.conversationId = conversationId; this.questionText = questionText;
        this.answerText = ""; this.visualization = "help"; this.resultJson = resultJson;
    }
    public void complete(String answer, String visualization, String resultJson) {
        this.answerText = answer; this.visualization = visualization; this.resultJson = resultJson;
        this.status = "complete"; this.error = null;
    }
    public void fail(String error) { this.status = "error"; this.error = error; }
    public UUID getConversationId() { return conversationId; }
    public String getQuestionText() { return questionText; }
    public String getAnswerText() { return answerText; }
    public String getVisualization() { return visualization; }
    public String getResultJson() { return resultJson; }
    public String getStatus() { return status; }
    public String getError() { return error; }
}
