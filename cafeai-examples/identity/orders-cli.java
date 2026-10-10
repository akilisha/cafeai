///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 23+
//DEPS com.akilisha.oss:cafeai-identity:0.6.0
//DEPS org.slf4j:slf4j-nop:2.0.19

// A terminal client for the orders API that signs in like `gh auth login` or `az login`, and
// calls the API with your token. Two ways to sign in:
//   - by default, a code and a link: you sign in on any device (RFC 8628; works over SSH too);
//   - with --browser, it opens the browser on this machine, and Keycloak sends it back to the
//     CLI on 127.0.0.1: no code to type (RFC 8252).
// The token is cached in ~/.cafeai/tokens/ (the same file either way), so you sign in once;
// `logout` revokes it at Keycloak and forgets it here.
//
//   jbang cafeai-examples/identity/orders-cli.java me             # who the API sees
//   jbang cafeai-examples/identity/orders-cli.java --browser me   # signing in through this machine's browser
//   jbang cafeai-examples/identity/orders-cli.java orders         # any signed-in user
//   jbang cafeai-examples/identity/orders-cli.java ledger         # finance only: alice yes, bob no
//   jbang cafeai-examples/identity/orders-cli.java logout
//
// Needs the demo Keycloak (cafeai-examples/identity/docker-compose.yml) and IdentityOrdersApi.

import io.cafeai.identity.DeviceLogin;
import io.cafeai.identity.IdentityException;
import io.cafeai.identity.Issuer;
import io.cafeai.identity.LoopbackLogin;
import io.cafeai.identity.SignedOut;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

class orders_cli {

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (IdentityException e) {
            System.out.println("Sign-in failed: " + e.getMessage());
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        String issuer = System.getenv().getOrDefault("ISSUER", "http://localhost:8180/realms/cafeai-demo");
        String api = System.getenv().getOrDefault("ORDERS_API", "http://localhost:8081");
        boolean browser = java.util.Arrays.asList(args).contains("--browser");
        String command = java.util.Arrays.stream(args).filter(a -> !a.startsWith("--")).findFirst().orElse("me");

        java.util.function.Supplier<String> accessToken;
        java.util.function.Supplier<SignedOut> signOut;
        if (browser) {
            var login = LoopbackLogin.of(Issuer.discover(issuer), "orders-cli").scope("openid");
            accessToken = login::accessToken;
            signOut = login::signOut;
        } else {
            var login = DeviceLogin.of(Issuer.discover(issuer), "orders-cli").scope("openid")
                .onPrompt(p -> {
                    System.out.println("To sign in, open " + p.verificationUri() + " and enter the code " + p.userCode());
                    if (p.verificationUriComplete() != null) {
                        System.out.println("or open " + p.verificationUriComplete());
                    }
                    System.out.println("Waiting for you to sign in...");
                });
            accessToken = login::accessToken;
            signOut = login::signOut;
        }

        if (command.equals("logout")) {
            System.out.println(switch (signOut.get()) {
                case REVOKED -> "Signed out of orders-cli: its sign-in is revoked at Keycloak and forgotten here.";
                case FORGOTTEN -> "Signed out of orders-cli here; Keycloak couldn't be told, so its sign-in lives until it expires.";
                case NOT_SIGNED_IN -> "orders-cli wasn't signed in.";
            });
            System.out.println("Your browser may still be signed in to Keycloak, and would approve the next sign-in"
                + " as the same person. To sign in as someone else, open the link in a private window.");
            return;
        }

        String token = accessToken.get();   // asks you to sign in only when it must
        HttpResponse<String> response;
        try {
            response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(api + "/" + command)).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            System.out.println("Signed in, but can't reach the orders API at " + api
                + ": is IdentityOrdersApi running?");
            System.out.println("  ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.IdentityOrdersApi");
            System.exit(1);
            return;
        }
        System.out.println(response.statusCode() + " " + response.body());
    }
}
