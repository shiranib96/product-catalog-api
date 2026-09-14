package nl.casus.catalog.product;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products")
class ProductSearchController {

    private final ProductService productService;

    ProductSearchController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/search")
    @Operation(summary = "Search products",
            description = "Searches SKU, name, brand, category, attributes and description. Every word has to match "
                    + "(partial words too) and results are ordered by relevance. When nothing matches, products with "
                    + "similarly spelled words are returned, so typos still find something.")
    PageResponse<ProductResponse> search(
            @RequestParam @NotBlank @Size(max = 200) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return productService.search(q, page, size);
    }
}
