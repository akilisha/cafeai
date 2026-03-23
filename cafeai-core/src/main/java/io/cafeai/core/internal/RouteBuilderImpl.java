package io.cafeai.core.internal;

import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.routing.Router;

/**
 * Fluent route builder returned by {@link Router#route(String)}.
 * All methods accept variadic {@link Middleware} handlers (ADR-009 §1).
 * Package-private — always accessed via {@link Router.RouteBuilder}.
 */
final class RouteBuilderImpl implements Router.RouteBuilder {

    private final String path;
    private final Router router;

    RouteBuilderImpl(String path, Router router) {
        this.path   = path;
        this.router = router;
    }

    @Override
    public Router.RouteBuilder get(Middleware... handlers) {
        router.get(path, handlers);
        return this;
    }

    @Override
    public Router.RouteBuilder post(Middleware... handlers) {
        router.post(path, handlers);
        return this;
    }

    @Override
    public Router.RouteBuilder put(Middleware... handlers) {
        router.put(path, handlers);
        return this;
    }

    @Override
    public Router.RouteBuilder patch(Middleware... handlers) {
        router.patch(path, handlers);
        return this;
    }

    @Override
    public Router.RouteBuilder delete(Middleware... handlers) {
        router.delete(path, handlers);
        return this;
    }

    @Override
    public Router.RouteBuilder head(Middleware... handlers) {
        router.head(path, handlers);
        return this;
    }

    @Override
    public Router.RouteBuilder options(Middleware... handlers) {
        router.options(path, handlers);
        return this;
    }

    @Override
    public Router.RouteBuilder all(Middleware... handlers) {
        router.all(path, handlers);
        return this;
    }
}
