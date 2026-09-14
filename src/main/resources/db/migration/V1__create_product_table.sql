CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE product
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku              VARCHAR(64)  NOT NULL UNIQUE,
    name             VARCHAR(255) NOT NULL,
    description      TEXT,
    brand            VARCHAR(255),
    category         VARCHAR(255),
    attributes       JSONB        NOT NULL DEFAULT '{}'::jsonb,

    -- only written by the price sync
    price_amount     NUMERIC(12, 2) CHECK (price_amount >= 0),
    price_currency   VARCHAR(3) CHECK (price_currency ~ '^[A-Z]{3}$'),
    price_updated_at TIMESTAMPTZ,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,

    -- 'simple' = no stemming, works better for brand names, SKUs and mixed Dutch/English text
    search_vector    TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(name, '')), 'A') ||
        setweight(to_tsvector('simple', sku), 'A') ||
        setweight(to_tsvector('simple', coalesce(brand, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(category, '')), 'B') ||
        setweight(jsonb_to_tsvector('simple', attributes, '["string", "numeric", "boolean"]'), 'C') ||
        setweight(to_tsvector('simple', coalesce(description, '')), 'D')
        ) STORED,

    -- for the fuzzy (typo) search
    search_text      TEXT GENERATED ALWAYS AS (
        lower(sku || ' ' || name || ' ' || coalesce(brand, '') || ' ' || coalesce(category, ''))
        ) STORED
);

CREATE INDEX product_search_vector_idx ON product USING GIN (search_vector);
CREATE INDEX product_search_text_trgm_idx ON product USING GIN (search_text gin_trgm_ops);
CREATE INDEX product_name_idx ON product (name, id);
