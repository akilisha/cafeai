package io.cafeai.core.internal;

import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.routing.Router;

import java.util.ArrayList;
import java.util.List;

/**
 * Concrete standalone {@link Router} implementation.
 *
 * <p>Returned by {@link io.cafeai.core.CafeAI#Router()}.
 * Mounted on an application via {@code app.use(path, router)}.
 *
 * <p>Internal -- always accessed via the {@code Router} interface.
 */
public final class SubRouter implements Router {

    // Consumed by CafeAIApp.registerRoutes() at mount time
    final List<RouteRegistration>      routes      = new ArrayList<>();
    final List<MiddlewareRegistration> middlewares = new ArrayList<>();

    @Override
    public Router get(String path, Middleware... handlers) {
        routes.add(new RouteRegistration("GET", path, CafeAIApp.compose(handlers)));
        return this;
    }

    @Override
    public Router post(String path, Middleware... handlers) {
        routes.add(new RouteRegistration("POST", path, CafeAIApp.compose(handlers)));
        return this;
    }

    @Override
    public Router put(String path, Middleware... handlers) {
        routes.add(new RouteRegistration("PUT", path, CafeAIApp.compose(handlers)));
        return this;
    }

    @Override
    public Router patch(String path, Middleware... handlers) {
        routes.add(new RouteRegistration("PATCH", path, CafeAIApp.compose(handlers)));
        return this;
    }

    @Override
    public Router delete(String path, Middleware... handlers) {
        routes.add(new RouteRegistration("DELETE", path, CafeAIApp.compose(handlers)));
        return this;
    }

    @Override
    public Router head(String path, Middleware... handlers) {
        routes.add(new RouteRegistration("HEAD", path, CafeAIApp.compose(handlers)));
        return this;
    }

    @Override
    public Router options(String path, Middleware... handlers) {
        routes.add(new RouteRegistration("OPTIONS", path, CafeAIApp.compose(handlers)));
        return this;
    }

    @Override
    public Router all(String path, Middleware... handlers) {
        Middleware composed = CafeAIApp.compose(handlers);
        for (String method : List.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS")) {
            routes.add(new RouteRegistration(method, path, composed));
        }
        return this;
    }

    @Override
    public Router use(Middleware... middlewares) {
        for (Middleware mw : middlewares) {
            this.middlewares.add(new MiddlewareRegistration(null, mw));
        }
        return this;
    }

    @Override
    public Router use(String path, Router subRouter) {
        routes.add(new RouteRegistration("_SUBROUTER_", path, Middleware.NOOP, subRouter));
        return this;
    }

    @Override
    public Router param(String name, ParamCallback callback) {
        routes.add(new RouteRegistration("_PARAM_", name,
            (req, res, next) -> callback.handle(req, res, next, req.params(name))));
        return this;
    }

    @Override
    public RouteBuilder route(String path) {
        return new RouteBuilderImpl(path, this);
    }

    // -- Internal types consumed by CafeAIApp ----------------------------------

    record RouteRegistration(
        String method,
        String path,
        Middleware handler,
        Router nestedRouter
    ) {
        RouteRegistration(String method, String path, Middleware handler) {
            this(method, path, handler, null);
        }
    }

    record MiddlewareRegistration(String path, Middleware middleware) {}
}
