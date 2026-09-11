package dev.chronovault.core.health;

import java.util.regex.Pattern;

/**
 * Scrubs likely secrets from command output before storing it.
 */
public final class SecretScrubber {
    private static final Pattern[] SECRET_PATTERNS = {
        Pattern.compile("(?i)(password\\s*[=:]\\s*)\\S+", Pattern.MULTILINE),
        Pattern.compile("(?i)(passwd\\s*[=:]\\s*)\\S+", Pattern.MULTILINE),
        Pattern.compile("(?i)(api[_-]?key\\s*[=:]\\s*)\\S+", Pattern.MULTILINE),
        Pattern.compile("(?i)(token\\s*[=:]\\s*)\\S+", Pattern.MULTILINE),
        Pattern.compile("(?i)(secret\\s*[=:]\\s*)\\S+", Pattern.MULTILINE),
        Pattern.compile("(?i)(authorization\\s*:\\s*)\\S+", Pattern.MULTILINE),
        Pattern.compile("(?i)(session[_-]?id\\s*[=:]\\s*)\\S+", Pattern.MULTILINE),
        Pattern.compile("(?i)(access[_-]?key\\s*[=:]\\s*)\\S+", Pattern.MULTILINE),
        Pattern.compile("(?i)(private[_-]?key\\s*[=:]\\s*)\\S+", Pattern.MULTILINE),
        Pattern.compile("(?i)(client[_-]?secret\\s*[=:]\\s*)\\S+", Pattern.MULTILINE),
        Pattern.compile("(?i)(\\\\S{20,}\\\\@[^\\\\s]+)", Pattern.MULTILINE),
    };

    public String scrub(String input) {
        if (input == null || input.isEmpty()) return input;
        String out = input;
        for (Pattern p : SECRET_PATTERNS) {
            out = p.matcher(out).replaceAll("$1[REDACTED]");
        }
        return out;
    }
}