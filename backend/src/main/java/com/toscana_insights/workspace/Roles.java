package com.toscana_insights.workspace;

/** SpEL expressions for @PreAuthorize; higher roles are listed explicitly instead of using a hierarchy. */
public final class Roles {
    private Roles() {}
    public static final String ADMIN_UP = "hasAnyRole('OWNER','ADMIN')";
    public static final String ANALYST_UP = "hasAnyRole('OWNER','ADMIN','ANALYST')";
    public static final String MEMBER = "hasAnyRole('OWNER','ADMIN','ANALYST','VIEWER')";
}
