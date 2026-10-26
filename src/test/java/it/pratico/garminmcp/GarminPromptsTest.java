package it.pratico.garminmcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GarminPromptsTest {

    private static String render(String name, Map<String, Object> args) {
        var spec = new GarminPrompts().specifications().stream()
                .filter(s -> s.prompt().name().equals(name)).findFirst().orElseThrow();
        var result = spec.promptHandler().apply(null, new McpSchema.GetPromptRequest(name, args));
        return ((McpSchema.TextContent) result.messages().getFirst().content()).text();
    }

    @Test
    void buildAndUpdateShareTheDashboardVersion() {
        String v = "\"v\": " + GarminPrompts.DASHBOARD_VERSION;
        String build = render("training-dashboard", Map.of("weeks", "12"));
        String update = render("training-dashboard-update", Map.of());
        assertTrue(build.contains(v));
        assertTrue(update.contains("its `v` is not " + GarminPrompts.DASHBOARD_VERSION));
        assertFalse(build.contains("{{") || update.contains("{{"));
    }

    @Test
    void updateNumbersTheBuildSectionsB1ToB3() {
        String update = render("training-dashboard-update", Map.of("weeks", "8"));
        assertTrue(update.contains("### B1. Collect the data"));
        assertTrue(update.contains("### B3. Build the dashboard"));
        assertTrue(update.contains("#### Time window"));
        assertFalse(update.contains("\n## 1. Collect"));
        assertTrue(update.contains("last **8 weeks**"));
    }
}
