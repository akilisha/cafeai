package io.cafeai.core.spi;

import io.cafeai.core.CafeAI;

/**
 * The integration seam between dependency injection and CafeAI bootstrap.
 *
 * <p>This is the bridge between Tier 1 (your DI container or manual wiring)
 * and Tier 2 (CafeAI configuration). See ADR-006 for the full three-tier
 * composition model.
 *
 * <p>Implement this interface to configure a CafeAI application with
 * injected dependencies. CafeAI discovers implementations via:
 * <ul>
 *   <li><b>Service Loader</b> -- list implementations in
 *       {@code META-INF/services/io.cafeai.core.spi.CafeAIConfigurer}</li>
 *   <li><b>Direct registration</b> -- call {@code CafeAI.create().configure(myConfigurer)}</li>
 * </ul>
 *
 * <p>Zero-DI example (no container needed):
 * <pre>{@code
 *   public class MyAppConfig implements CafeAIConfigurer {
 *       private final UserService userService;
 *
 *       public MyAppConfig(UserService userService) {
 *           this.userService = userService;
 *       }
 *
 *       @Override
 *       public void configure(CafeAI app) {
 *           app.ai(OpenAI.of("gpt-4o"));
 *           app.get("/users/:id", (req, res, next) ->
 *               res.json(userService.find(req.params("id"))));
 *       }
 *   }
 *
 *   // In main():
 *   var app = CafeAI.create();
 *   app.configure(new MyAppConfig(new UserService(dataSource)));
 *   app.listen(8080);
 * }</pre>
 */
@FunctionalInterface
public interface CafeAIConfigurer {

    /**
     * Configures the CafeAI application.
     *
     * <p>Called exactly once per application lifecycle, before {@code app.listen()}.
     *
     * @param app the CafeAI application to configure -- never null
     */
    void configure(CafeAI app);

    /**
     * Execution order when multiple configurers are present.
     * Lower values execute first. Default is {@code 0}.
     *
     * <p>Use this to ensure infrastructure configurers (AI provider,
     * memory strategy) run before route-registering configurers.
     */
    default int order() {
        return 0;
    }
}
