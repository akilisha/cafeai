package io.cafeai.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link ToolRegistry} — annotation scanning, tool invocation,
 * error handling, and the external MCP tool registration path.
 */
@DisplayName("ToolRegistry")
class ToolRegistryTest {

    // ── Sample tool classes ───────────────────────────────────────────────────

    static class OrderTools {

        @CafeAITool("Look up order status by order ID")
        public String getOrderStatus(String orderId) {
            return switch (orderId) {
                case "ORD-001" -> "Shipped";
                case "ORD-002" -> "Processing";
                default        -> "Not found";
            };
        }

        @CafeAITool("Calculate total price including tax")
        public String calculateTotal(String price, String taxRate) {
            try {
                double p = Double.parseDouble(price);
                double t = Double.parseDouble(taxRate);
                return String.format("%.2f", p * (1 + t / 100));
            } catch (NumberFormatException e) {
                return "ERROR: invalid number";
            }
        }
    }

    static class ThrowingTools {
        @CafeAITool("A tool that always throws")
        public String alwaysFails(String input) {
            throw new RuntimeException("Intentional failure for test");
        }
    }

    static class NoAnnotationTools {
        public String notATool(String input) { return input; }
    }

    // ── Registration tests ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tool registration")
    class RegistrationTests {

        @Test
        @DisplayName("register() scans @CafeAITool methods")
        void register_scansAnnotations() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new OrderTools());

            assertThat(registry.hasTools()).isTrue();
            assertThat(registry.allTools()).hasSize(2);
        }

        @Test
        @DisplayName("Registered tools have correct names from annotation")
        void register_toolNames() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new OrderTools());

            assertThat(registry.allTools())
                .extracting(ToolDefinition::name)
                .contains("getOrderStatus", "calculateTotal");
        }

        @Test
        @DisplayName("Registered tools have descriptions from annotation value")
        void register_toolDescriptions() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new OrderTools());

            assertThat(registry.allTools())
                .extracting(ToolDefinition::description)
                .contains("Look up order status by order ID",
                          "Calculate total price including tax");
        }

        @Test
        @DisplayName("Tools registered from @CafeAITool have INTERNAL trust level")
        void register_internalTrustLevel() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new OrderTools());

            registry.allTools().forEach(t ->
                assertThat(t.trustLevel())
                    .isEqualTo(ToolDefinition.TrustLevel.INTERNAL));
        }

        @Test
        @DisplayName("register() throws when no @CafeAITool methods found")
        void register_noAnnotations_throws() {
            ToolRegistry registry = new ToolRegistry();

            assertThatThrownBy(() -> registry.register(new NoAnnotationTools()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@CafeAITool");
        }

        @Test
        @DisplayName("registerExternal() adds EXTERNAL trust level tools")
        void registerExternal_externalTrustLevel() {
            ToolRegistry registry = new ToolRegistry();
            registry.registerExternal("fetch-issue",
                "Fetch a GitHub issue by number",
                java.util.List.of(new ToolDefinition.ParameterSchema(
                    "issueNumber", "string", "The GitHub issue number")));

            assertThat(registry.hasTools()).isTrue();
            assertThat(registry.allTools())
                .extracting(ToolDefinition::trustLevel)
                .containsOnly(ToolDefinition.TrustLevel.EXTERNAL);
        }
    }

    // ── Invocation tests ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tool invocation")
    class InvocationTests {

        @Test
        @DisplayName("Tool invokes correctly and returns string result")
        void invoke_returnsResult() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new OrderTools());

            ToolDefinition tool = registry.allTools().stream()
                .filter(t -> t.name().equals("getOrderStatus"))
                .findFirst().orElseThrow();

            String result = tool.invoke("ORD-001");

            assertThat(result).isEqualTo("Shipped");
        }

        @Test
        @DisplayName("Tool invocation with multiple parameters")
        void invoke_multipleParams() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new OrderTools());

            ToolDefinition tool = registry.allTools().stream()
                .filter(t -> t.name().equals("calculateTotal"))
                .findFirst().orElseThrow();

            String result = tool.invoke("100", "20");

            assertThat(result).isEqualTo("120.00");
        }

        @Test
        @DisplayName("Tool exception is caught and returned as ERROR: string")
        void invoke_exceptionCaught_returnsError() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new ThrowingTools());

            ToolDefinition tool = registry.allTools().stream()
                .findFirst().orElseThrow();

            String result = tool.invoke("anything");

            assertThat(result).startsWith("ERROR:");
            assertThat(result).contains("Intentional failure");
        }

        @Test
        @DisplayName("Tool exception never propagates out of invoke()")
        void invoke_exceptionNeverPropagates() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new ThrowingTools());

            ToolDefinition tool = registry.allTools().stream().findFirst().orElseThrow();

            // Must not throw
            assertThatCode(() -> tool.invoke("test")).doesNotThrowAnyException();
        }
    }

    // ── ToolDefinition parameter schema ───────────────────────────────────────

    @Nested
    @DisplayName("ToolDefinition parameter schema")
    class ParameterSchemaTests {

        @Test
        @DisplayName("Parameters are extracted from method signature")
        void parameters_extractedFromMethod() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new OrderTools());

            ToolDefinition getStatus = registry.allTools().stream()
                .filter(t -> t.name().equals("getOrderStatus"))
                .findFirst().orElseThrow();

            assertThat(getStatus.parameters()).hasSize(1);
            assertThat(getStatus.parameters().get(0).type()).isEqualTo("string");
        }

        @Test
        @DisplayName("Multi-parameter tool has correct schema size")
        void parameters_multiParam() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new OrderTools());

            ToolDefinition calcTotal = registry.allTools().stream()
                .filter(t -> t.name().equals("calculateTotal"))
                .findFirst().orElseThrow();

            assertThat(calcTotal.parameters()).hasSize(2);
        }
    }
}
