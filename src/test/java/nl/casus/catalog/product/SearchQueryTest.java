package nl.casus.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SearchQueryTest {

    @Test
    void requiresEveryTermAsPrefix() {
        assertThat(SearchQuery.parse("Wireless Headphones"))
                .contains(new SearchQuery("wireless headphones", "wireless:* & headphones:*"));
    }

    @Test
    void splitsTermsTheWayPostgresTokenizesThem() {
        assertThat(SearchQuery.parse("WH-1000XM5"))
                .contains(new SearchQuery("wh 1000xm5", "wh:* & 1000xm5:*"));
    }

    @Test
    void neutralisesTsQueryOperators() {
        assertThat(SearchQuery.parse("shoes & !socks | (red):* <-> 'x'"))
                .contains(new SearchQuery("shoes socks red x", "shoes:* & socks:* & red:* & x:*"));
    }

    @Test
    void keepsNonAsciiLetters() {
        assertThat(SearchQuery.parse("Café Crème"))
                .contains(new SearchQuery("café crème", "café:* & crème:*"));
    }

    @Test
    void ignoresRepeatedTerms() {
        assertThat(SearchQuery.parse("red RED red"))
                .contains(new SearchQuery("red", "red:*"));
    }

    @Test
    void limitsTheNumberOfTerms() {
        String input = IntStream.rangeClosed(1, 20).mapToObj(i -> "t" + i).collect(Collectors.joining(" "));

        assertThat(SearchQuery.parse(input)).hasValueSatisfying(query ->
                assertThat(query.tsQuery().split(" & ")).hasSize(SearchQuery.MAX_TERMS));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "!!!", "& | :*"})
    void hasNothingToSearchForWithoutWords(String input) {
        assertThat(SearchQuery.parse(input)).isEmpty();
    }
}
