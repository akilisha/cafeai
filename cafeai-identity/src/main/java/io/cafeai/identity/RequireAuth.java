package io.cafeai.identity;

import io.cafeai.core.identity.Identity;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Lets a request through only if its caller meets every requirement. Created by
 * {@link Auth#require(Requirement...)}.
 *
 * <ul>
 *   <li>no identity on the request (anonymous, or no {@code Auth.bearer} in front):
 *       {@code 401} with {@code WWW-Authenticate: Bearer};</li>
 *   <li>a requirement not met: {@code 403}. When scopes would satisfy it, the challenge is
 *       {@code error="insufficient_scope"} naming them (RFC 6750 3.1); roles, groups and
 *       entitlements are never named to the caller.</li>
 * </ul>
 */
final class RequireAuth implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(RequireAuth.class);

    private final List<Requirement> requirements;

    RequireAuth(List<Requirement> requirements) {
        if (requirements.isEmpty()) throw new IllegalArgumentException("Name at least one requirement");
        this.requirements = List.copyOf(requirements);
    }

    @Override
    public void handle(Request req, Response res, Next next) {
        Optional<Identity> identity = req.identity();
        if (identity.isEmpty()) {
            res.status(401).set("WWW-Authenticate", "Bearer").end();
            return;
        }
        for (Requirement requirement : requirements) {
            if (!requirement.test(identity.get())) {
                log.debug("{} refused: needs {}", identity.get(), requirement);
                Set<String> scopes = requirement.scopes();
                if (scopes.isEmpty()) {
                    res.status(403).end();
                } else {
                    res.status(403).set("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\""
                            + String.join(" ", scopes) + "\"").end();
                }
                return;
            }
        }
        next.run();
    }
}
