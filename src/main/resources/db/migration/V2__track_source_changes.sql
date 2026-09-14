-- when the product data (not the price) was last changed at the source, so older data can't overwrite newer data
ALTER TABLE product ADD COLUMN source_updated_at TIMESTAMPTZ;

-- all writes are conditional statements now, so no optimistic locking needed
ALTER TABLE product DROP COLUMN version;
