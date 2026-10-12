package com.toscana_insights.datasource;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataSourceControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void storesOnlyCredentialReferenceMetadata() throws Exception {
        String request = """
                {"name":"Warehouse","type":"SQL_SERVER","host":"db.internal","port":1433,
                 "databaseName":"sales","username":"reader","credentialReference":"vault://toscana_insights/sales"}
                """;
        mvc.perform(post("/api/data-sources").with(httpBasic("demo@toscana-insights.local", "demo123!"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.credentialReference").value("vault://toscana_insights/sales"))
                .andExpect(jsonPath("$", not(hasKey("password"))));
    }

    @Test
    void listsSupportedSourceTypes() throws Exception {
        mvc.perform(get("/api/data-sources/types").with(httpBasic("demo@toscana-insights.local", "demo123!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("SQL_SERVER"))
                .andExpect(jsonPath("$[4]").value("SHAREPOINT"))
                .andExpect(jsonPath("$[6]").value("FILE_DUMP"));
    }
}
