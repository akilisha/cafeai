package io.cafeai.tools;

import java.lang.annotation.*;

/**
 * Marks a method as an LLM tool — a Java function the model can invoke
 * during its reasoning process.
 *
 * <p>Register tool-bearing objects via {@code app.tool(instance)}.
 * CafeAI scans for {@code @CafeAITool}-annotated methods and exposes them
 * to the LLM with their name, description, and parameter schemas.
 *
 * <pre>{@code
 *   public class OrderTools {
 *
 *       @CafeAITool("Look up an order by ID and return its current status")
 *       public String getOrderStatus(String orderId) {
 *           return orderService.find(orderId).status().name();
 *       }
 *
 *       @CafeAITool("Cancel an order. Returns 'cancelled' or an error message.")
 *       public String cancelOrder(String orderId, String reason) {
 *           return orderService.cancel(orderId, reason);
 *       }
 *   }
 *
 *   app.tool(new OrderTools(orderService));
 * }</pre>
 *
 * <p>Tool methods must:
 * <ul>
 *   <li>Be {@code public}</li>
 *   <li>Have parameters of type {@code String}, {@code int}, {@code long},
 *       {@code double}, {@code boolean}, or {@code String[]}</li>
 *   <li>Return {@code String} (the result sent back to the LLM)</li>
 *   <li>Not throw checked exceptions — catch them and return an error string</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CafeAITool {

    /**
     * Human-readable description of what the tool does.
     * The LLM uses this to decide when to invoke the tool.
     * Be specific — a clear description leads to better tool selection.
     */
    String value();

    /**
     * Optional tool name override. Defaults to the Java method name.
     * Useful when the Java name isn't descriptive enough for the LLM.
     */
    String name() default "";
}
