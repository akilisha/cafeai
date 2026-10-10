package io.cafeai.core.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Receives captured {@link Transcript}s. Called synchronously on the thread that made the model
 * call, so keep it quick; a sink that throws is logged and skipped, and never fails the call.
 * Honouring {@link Transcript#keepUntil()} is the sink's job; {@link #jsonLines(Path)} does it.
 */
@FunctionalInterface
public interface TranscriptSink {

    /** Receives one transcript. */
    void record(Transcript transcript);

    /**
     * One JSON object per line, in files named by the day their records must be gone
     * ({@code until-2026-11-09.jsonl}): every record in a file expires that day, so retention is
     * deleting a file once its day has passed, which this sink does as it writes. A record is
     * never kept past the end of its {@code keepUntil} day (UTC).
     */
    static TranscriptSink jsonLines(Path directory) {
        return new JsonLines(directory, Clock.systemUTC());
    }

    /** {@link #jsonLines(Path)}, the files' days decided by {@code clock}. */
    final class JsonLines implements TranscriptSink {
        private static final Pattern NAME = Pattern.compile("until-(\\d{4}-\\d{2}-\\d{2})\\.jsonl");
        private static final ObjectMapper JSON = new ObjectMapper();

        private final Path directory;
        private final Clock clock;
        private volatile LocalDate purgedOn;

        JsonLines(Path directory, Clock clock) {
            this.directory = Objects.requireNonNull(directory, "directory");
            this.clock = clock;
        }

        @Override
        public synchronized void record(Transcript t) {
            purgeExpired();
            ObjectNode line = JSON.createObjectNode()
                    .put("at", t.at().toString())
                    .put("issuer", t.caller() == null ? null : t.caller().issuer())
                    .put("subject", t.caller() == null ? null : t.caller().subject())
                    .put("route", t.route())
                    .put("model", t.model())
                    .put("prompt", t.prompt())
                    .put("answer", t.answer())
                    .put("keepUntil", t.keepUntil().toString());
            LocalDate until = LocalDate.ofInstant(t.keepUntil(), ZoneOffset.UTC);
            try {
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("until-" + until + ".jsonl"),
                        JSON.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not write a transcript to " + directory, e);
            }
        }

        /** Deletes the files whose day has passed; at most once a day. */
        void purgeExpired() {
            LocalDate today = LocalDate.now(clock);
            if (today.equals(purgedOn) || !Files.isDirectory(directory)) return;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "until-*.jsonl")) {
                for (Path file : files) {
                    Matcher m = NAME.matcher(file.getFileName().toString());
                    if (!m.matches()) continue;
                    try {
                        if (LocalDate.parse(m.group(1)).isBefore(today)) Files.deleteIfExists(file);
                    } catch (DateTimeParseException ignored) {
                        // not one of ours
                    }
                }
                purgedOn = today;
            } catch (IOException e) {
                throw new UncheckedIOException("Could not purge expired transcripts in " + directory, e);
            }
        }
    }
}
