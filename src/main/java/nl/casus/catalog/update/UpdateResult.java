package nl.casus.catalog.update;

import java.util.List;

public record UpdateResult(Counts products, Counts prices, List<String> unknownProducts) {

    public record Counts(int applied, int stale) {
    }
}
