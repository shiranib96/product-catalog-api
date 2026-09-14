package nl.casus.catalog.product;

import static java.util.stream.Collectors.joining;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

// text: the terms separated by spaces (for fuzzy matching)
// tsQuery: every term as a prefix, all required, e.g. "black:* & coffee:*"
record SearchQuery(String text, String tsQuery) {

    static final int MAX_TERMS = 10;

    // splits the same way postgres tokenizes the text, and gets rid of tsquery operators (& | ! : * etc.)
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    static Optional<SearchQuery> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        List<String> terms = NON_WORD.splitAsStream(input.toLowerCase(Locale.ROOT))
                .filter(term -> !term.isEmpty())
                .distinct()
                .limit(MAX_TERMS)
                .toList();
        if (terms.isEmpty()) {
            return Optional.empty();
        }
        String tsQuery = terms.stream().map(term -> term + ":*").collect(joining(" & "));
        return Optional.of(new SearchQuery(String.join(" ", terms), tsQuery));
    }
}
