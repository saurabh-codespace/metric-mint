package com.toscana_insights.common;

public class BudgetExceededException extends RuntimeException {
    public BudgetExceededException(String message) { super(message); }
}
