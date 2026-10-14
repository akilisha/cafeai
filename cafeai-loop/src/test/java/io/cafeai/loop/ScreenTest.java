package io.cafeai.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ROADMAP-03, 2.4: personal data and secrets become stable placeholders on the way to the model,
 * and are restored exactly on the way back, so code written back is never corrupted.
 */
@DisplayName("The screen: placeholders out, the originals restored exactly")
class ScreenTest {

    private final Screen screen = new Screen();

    @Test @DisplayName("an email address, a phone number and a secret leave as placeholders")
    void out() {
        String text = "Mail alice@acme.example or call 555-867-5309. Key: AKIAIOSFODNN7EXAMPLE.";
        String sent = screen.out(text);
        assertThat(sent).doesNotContain("alice@acme.example").doesNotContain("555-867-5309").doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(sent).containsPattern("\\[[A-Z_]+_1_[0-9a-f]{4}\\]");
        assertThat(sent).startsWith("Mail [").contains(" or call [").contains("Key: [");
    }

    @Test @DisplayName("the same value always gets the same placeholder; different values, different ones")
    void stable() {
        String first = screen.out("alice@acme.example wrote to bob@acme.example");
        String again = screen.out("Ask alice@acme.example again");
        String alice = first.substring(0, first.indexOf(' '));
        assertThat(again).isEqualTo("Ask " + alice + " again");
        assertThat(first).doesNotContain(alice + " wrote to " + alice);
    }

    @ParameterizedTest(name = "round trip: {0}")
    @ValueSource(strings = {
            "plain text with nothing to hide",
            "String admin = \"alice@acme.example\";\r\nString backup = \"alice@acme.example\";\n",
            "Unicode survives: café, Ünïcödé, ﬁ ligature, zero​width, then bob@acme.example",
            "tel: (555) 867-5309, +1 555 867 5309",
            "token=ghp_0123456789abcdefghijABCDEFGHIJ0123456789 in a config",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA0Z3VS5JJcds3xfn\n-----END RSA PRIVATE KEY-----",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
            ""})
    @DisplayName("in(out(text)) is the text, byte for byte")
    void roundTrip(String text) {
        assertThat(screen.in(screen.out(text))).isEqualTo(text);
    }

    @Test @DisplayName("text with nothing to screen leaves untouched, invisible characters and all")
    void untouched() {
        String code = "class App {\r\n\tint x = 1;​ // café\n}\n";
        assertThat(screen.out(code)).isEqualTo(code);
    }

    @Test @DisplayName("what the model writes back with placeholders is restored, even rearranged")
    void restoreRearranged() {
        String sent = screen.out("owner: alice@acme.example, support: help@acme.example");
        String alice = sent.substring("owner: ".length(), sent.indexOf(','));
        String help = sent.substring(sent.lastIndexOf(' ') + 1);
        String written = "support = " + help + "\nowner = " + alice + "\nowner again = " + alice + "\n";
        assertThat(screen.in(written)).isEqualTo(
                "support = help@acme.example\nowner = alice@acme.example\nowner again = alice@acme.example\n");
    }

    @Test @DisplayName("a placeholder the screen didn't make is left alone")
    void unknownPlaceholder() {
        assertThat(screen.in("see [EMAIL_9] and [NOT_OURS]")).isEqualTo("see [EMAIL_9] and [NOT_OURS]");
    }

    @Test @DisplayName("a placeholder is never screened again")
    void notTwice() {
        String once = screen.out("alice@acme.example");
        assertThat(screen.out(once)).isEqualTo(once);
        assertThat(screen.in(screen.out(once))).isEqualTo("alice@acme.example");
    }

    @Test @DisplayName("a value that looks like a placeholder in the original is restored as it was")
    void literalPlaceholderText() {
        String text = "literal [EMAIL_1] in the docs, and alice@acme.example";
        assertThat(screen.in(screen.out(text))).isEqualTo(text);
    }

    @Test @DisplayName("none() changes nothing: the developer's own tools")
    void none() {
        assertThat(Screen.none().out("alice@acme.example")).isEqualTo("alice@acme.example");
        assertThat(Screen.none().in("[EMAIL_1]")).isEqualTo("[EMAIL_1]");
    }

    @Test @DisplayName("a screen given a tag uses it, the same every time (the evals' recordings replay)")
    void givenTag() {
        assertThat(new Screen("e7a1").out("Mail alice@acme.example")).isEqualTo("Mail [EMAIL_1_e7a1]");
        assertThat(new Screen("e7a1").out("Mail alice@acme.example")).isEqualTo(new Screen("e7a1").out("Mail alice@acme.example"));
    }

    @Test @DisplayName("null passes through")
    void nulls() {
        assertThat(screen.out(null)).isNull();
        assertThat(screen.in(null)).isNull();
    }
}
