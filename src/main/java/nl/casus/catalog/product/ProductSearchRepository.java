package nl.casus.catalog.product;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ProductSearchRepository {

    private static final String FULL_TEXT_MATCH = "p.search_vector @@ to_tsquery('simple', :tsQuery)";
    private static final String FULL_TEXT_RANK = "ts_rank_cd(p.search_vector, to_tsquery('simple', :tsQuery))";
    private static final String FUZZY_MATCH = ":text <% p.search_text";
    private static final String FUZZY_RANK = "word_similarity(:text, p.search_text)";

    private final JdbcClient jdbc;

    ProductSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    SearchHits search(SearchQuery query, int page, int size) {
        SearchHits hits = find(FULL_TEXT_MATCH, FULL_TEXT_RANK, query, page, size);
        if (hits.total() > 0) {
            return hits;
        }
        // nothing found, try again with fuzzy matching to handle typos
        return find(FUZZY_MATCH, FUZZY_RANK, query, page, size);
    }

    private SearchHits find(String match, String rank, SearchQuery query, int page, int size) {
        List<Hit> hits = jdbc.sql("""
                        SELECT p.id, count(*) OVER () AS total
                          FROM product p
                         WHERE %s
                         ORDER BY %s DESC, p.name, p.id
                         LIMIT :limit OFFSET :offset
                        """.formatted(match, rank))
                .param("tsQuery", query.tsQuery())
                .param("text", query.text())
                .param("limit", size)
                .param("offset", (long) page * size)
                .query((row, rowNumber) -> new Hit(row.getLong("id"), row.getLong("total")))
                .list();
        if (!hits.isEmpty()) {
            return new SearchHits(hits.stream().map(Hit::id).toList(), hits.getFirst().total());
        }
        if (page == 0) {
            return new SearchHits(List.of(), 0);
        }
        // page is past the last result, still need the total
        long total = jdbc.sql("SELECT count(*) FROM product p WHERE " + match)
                .param("tsQuery", query.tsQuery())
                .param("text", query.text())
                .query(Long.class)
                .single();
        return new SearchHits(List.of(), total);
    }

    private record Hit(long id, long total) {
    }

    record SearchHits(List<Long> ids, long total) {
    }
}
