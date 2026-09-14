package nl.casus.catalog.source;

import java.util.List;

record SourceProductPage(List<SourceProduct> items, int page, int size, long totalItems, int totalPages) {
}
