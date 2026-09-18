package io.cafeai.core.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Content negotiation (RFC 9110 §12) and Cookie header parsing (RFC 6265 §5.4). */
class NegotiationTest {

    @Nested
    @DisplayName("Accept (media types)")
    class Media {

        @Test @DisplayName("no header: the client accepts anything, so the first offer wins")
        void noHeader() {
            assertThat(Negotiation.media(null, "text/html", "application/json")).isEqualTo("text/html");
            assertThat(Negotiation.media("  ", "text/html")).isEqualTo("text/html");
        }

        @Test @DisplayName("picks the offered type the client names")
        void exact() {
            assertThat(Negotiation.media("application/json", "text/html", "application/json"))
                .isEqualTo("application/json");
        }

        @Test @DisplayName("higher q wins — the old substring match ignored q and picked html here")
        void qValues() {
            assertThat(Negotiation.media("text/html;q=0.1, application/json;q=0.9",
                                         "text/html", "application/json"))
                .isEqualTo("application/json");
        }

        @Test @DisplayName("a more specific range decides an offer's quality")
        void specificity() {
            assertThat(Negotiation.media("text/*;q=0.5, text/html;q=1", "text/plain", "text/html"))
                .isEqualTo("text/html");
            assertThat(Negotiation.media("text/*", "application/json", "text/html"))
                .isEqualTo("text/html");
        }

        @Test @DisplayName("q=0 refuses a type even when a wildcard would allow it")
        void qZeroRefuses() {
            assertThat(Negotiation.media("application/json;q=0, */*;q=0.1",
                                         "application/json", "text/html"))
                .isEqualTo("text/html");
        }

        @Test @DisplayName("Express short names match, and the caller's own spelling is returned")
        void shortNames() {
            assertThat(Negotiation.media("application/json", "html", "json")).isEqualTo("json");
            assertThat(Negotiation.media("text/html,*/*;q=0.1", "json", "html")).isEqualTo("html");
        }

        @Test @DisplayName("nothing acceptable, or nothing offered: null")
        void none() {
            assertThat(Negotiation.media("image/png", "text/html", "application/json")).isNull();
            assertThat(Negotiation.media("text/html")).isNull();
        }

        @Test @DisplayName("a malformed q is ignored rather than failing the request")
        void malformedQ() {
            assertThat(Negotiation.media("application/json;q=banana", "application/json"))
                .isEqualTo("application/json");
        }
    }

    @Nested
    @DisplayName("Accept-Language / -Charset / -Encoding")
    class Tokens {

        @Test @DisplayName("language: a range matches more specific tags (en → en-US)")
        void languagePrefix() {
            assertThat(Negotiation.language("en", "fr", "en-US")).isEqualTo("en-US");
        }

        @Test @DisplayName("language: q-values order the choice")
        void languageQ() {
            assertThat(Negotiation.language("fr;q=0.9, en;q=0.5", "en", "fr")).isEqualTo("fr");
        }

        @Test @DisplayName("language: no match is null, and * matches anything")
        void languageNone() {
            assertThat(Negotiation.language("de", "en", "fr")).isNull();
            assertThat(Negotiation.language("*", "en", "fr")).isEqualTo("en");
        }

        @Test @DisplayName("encoding and charset: exact, case-insensitive, with q")
        void encodingAndCharset() {
            assertThat(Negotiation.encoding("gzip, br;q=0.5", "br", "gzip")).isEqualTo("gzip");
            assertThat(Negotiation.charset("UTF-8, iso-8859-1;q=0", "iso-8859-1", "utf-8")).isEqualTo("utf-8");
        }
    }

    @Nested
    @DisplayName("Cookie header")
    class Cookies {

        @Test @DisplayName("parses name=value pairs")
        void pairs() {
            assertThat(CookieHeader.parse("a=1; b=two;c=3"))
                .containsExactly(Map.entry("a", "1"), Map.entry("b", "two"), Map.entry("c", "3"));
        }

        @Test @DisplayName("no header or an empty one: an empty map")
        void empty() {
            assertThat(CookieHeader.parse(null)).isEmpty();
            assertThat(CookieHeader.parse("   ")).isEmpty();
        }

        @Test @DisplayName("strips quotes; percent-decodes valid encodings; a '+' stays a plus")
        void values() {
            assertThat(CookieHeader.parse("q=\"quoted\"")).containsEntry("q", "quoted");
            assertThat(CookieHeader.parse("d=hello%20world")).containsEntry("d", "hello world");
            assertThat(CookieHeader.parse("p=a+b")).containsEntry("p", "a+b");
            assertThat(CookieHeader.parse("p=a%2Bb")).containsEntry("p", "a+b");
        }

        @Test @DisplayName("invalid percent-encoding is left as sent, not an exception")
        void badEncoding() {
            assertThat(CookieHeader.parse("x=100%")).containsEntry("x", "100%");
        }

        @Test @DisplayName("the first of a repeated name wins; nameless and '='-less pairs are skipped")
        void duplicatesAndJunk() {
            assertThat(CookieHeader.parse("a=1; a=2; =nameless; junk; b=3"))
                .containsExactly(Map.entry("a", "1"), Map.entry("b", "3"));
        }
    }
}
