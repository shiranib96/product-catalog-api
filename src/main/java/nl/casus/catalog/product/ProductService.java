package nl.casus.catalog.product;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import java.util.List;
import java.util.Map;
import nl.casus.catalog.product.ProductSearchRepository.SearchHits;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository products;
    private final ProductSearchRepository searchRepository;

    ProductService(ProductRepository products, ProductSearchRepository searchRepository) {
        this.products = products;
        this.searchRepository = searchRepository;
    }

    public PageResponse<ProductResponse> search(String text, int page, int size) {
        return SearchQuery.parse(text)
                .map(query -> search(query, page, size))
                .orElseGet(() -> PageResponse.of(List.of(), page, size, 0));
    }

    private PageResponse<ProductResponse> search(SearchQuery query, int page, int size) {
        SearchHits hits = searchRepository.search(query, page, size);
        Map<Long, Product> productsById = products.findAllById(hits.ids()).stream()
                .collect(toMap(Product::getId, identity()));
        // findAllById doesn't keep the order, so put them back in ranked order
        List<ProductResponse> items = hits.ids().stream()
                .map(productsById::get)
                .map(ProductResponse::from)
                .toList();
        return PageResponse.of(items, page, size, hits.total());
    }
}
