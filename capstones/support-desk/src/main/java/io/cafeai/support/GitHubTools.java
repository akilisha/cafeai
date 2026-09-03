package io.cafeai.support;

import dev.langchain4j.agent.tool.Tool;

/**
 * Tools that give the LLM access to live GitHub data.
 *
 * Handed to the support agent via {@code app.agent(...).tool(new GitHubTools())};
 * LangChain4j exposes each {@code @Tool} method to the model, which decides when
 * to call them based on the conversation.
 *
 * In production: replace the stub returns with real HTTP calls
 * to api.github.com using the GitHub REST API.
 */
public class GitHubTools {

    @Tool("Fetch the current status of a Helios GitHub issue by its number. " +
                "Returns the issue title, state (open/closed), and latest comment.")
    public String getIssueStatus(String issueNumber) {
        // Simulated GitHub API response
        // Real implementation: GET https://api.github.com/repos/helios-pool/helios/issues/{issueNumber}
        return switch (issueNumber.trim()) {
            case "142" -> """
                Issue #142: Connection leak under high concurrency
                State: CLOSED (fixed in v2.1.0)
                Resolution: Improved lock contention in connection acquisition path.
                Upgrade to 2.1.0 or later to get this fix.""";

            case "156" -> """
                Issue #156: maxLifetime not honoured when database restarts
                State: OPEN
                Labels: bug, needs-investigation
                Latest comment (3 days ago): Reproduced on PostgreSQL 15.
                Workaround: set keepaliveTime=30000 to detect stale connections.""";

            case "171" -> """
                Issue #171: NullPointerException with special characters in jdbcUrl
                State: CLOSED (fixed in v2.0.8)
                Resolution: URL is now encoded before passing to JDBC driver.""";

            case "189" -> """
                Issue #189: leakDetectionThreshold fires incorrectly for long transactions
                State: OPEN
                Labels: bug, workaround-available
                Workaround: set leakDetectionThreshold higher than your longest transaction.""";

            default -> "Issue #" + issueNumber + " not found in the Helios repository. " +
                       "Check the issue number at https://github.com/helios-pool/helios/issues";
        };
    }

    @Tool("Search open Helios GitHub issues by keyword. " +
                "Use this when the user describes a problem but doesn't know the issue number.")
    public String searchIssues(String keyword) {
        // Simulated search
        // Real: GET https://api.github.com/search/issues?q={keyword}+repo:helios-pool/helios
        String lower = keyword.toLowerCase();

        if (lower.contains("timeout") || lower.contains("connection")) {
            return """
                Found 2 open issues matching '%s':
                - #156: maxLifetime not honoured when database restarts (open)
                - #189: leakDetectionThreshold fires incorrectly for long transactions (open)
                Use getIssueStatus to get full details on either.""".formatted(keyword);
        }

        if (lower.contains("null") || lower.contains("npe") || lower.contains("exception")) {
            return """
                Found 1 closed issue matching '%s':
                - #171: NullPointerException with special characters in jdbcUrl (closed, fixed in v2.0.8)""".formatted(keyword);
        }

        if (lower.contains("spring") || lower.contains("boot")) {
            return "No open issues matching Spring Boot integration. " +
                   "Spring Boot autoconfiguration is supported — see the docs.";
        }

        return "No open issues found matching '" + keyword + "'. " +
               "The Helios issue tracker is at https://github.com/helios-pool/helios/issues";
    }

    @Tool("Get the latest Helios release version and its changelog summary.")
    public String getLatestRelease() {
        // Real: GET https://api.github.com/repos/helios-pool/helios/releases/latest
        return """
            Latest release: Helios v2.1.0 (released 2026-02-14)
            
            Highlights:
            - Fixed connection leak under high concurrency (#142)
            - Improved performance of connection acquisition by 23%
            - Added support for virtual threads (Java 21+)
            - New: connectionTestQuery auto-detection for PostgreSQL, MySQL, H2
            
            Migration from 2.0.x: No breaking changes. Drop-in upgrade.""";
    }
}
