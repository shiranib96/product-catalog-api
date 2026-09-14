package nl.casus.catalog.product;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

// read-only on purpose, writes go through CatalogWriter
public interface ProductRepository extends Repository<Product, Long> {

    Optional<Product> findBySku(String sku);

    List<Product> findAllById(Iterable<Long> ids);

    long count();
}
