-- The latest interest rates fetched from the web, kept so they are not fetched again and again. One row; the body is JSON.
CREATE TABLE market_rates (
    id         INT       NOT NULL PRIMARY KEY,
    body       TEXT      NOT NULL,
    fetched_at TIMESTAMP NOT NULL
);
