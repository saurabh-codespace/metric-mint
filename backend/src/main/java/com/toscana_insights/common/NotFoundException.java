package com.toscana_insights.common;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String resource, Object id) { super(resource + " not found: " + id); }
}
