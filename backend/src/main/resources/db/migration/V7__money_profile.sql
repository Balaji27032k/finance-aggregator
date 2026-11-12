-- The person's own details (goals, safety net, holdings, loans, rate assumptions) used by the money coach. One row; the body is JSON.
CREATE TABLE money_profile (
    id         INT       NOT NULL PRIMARY KEY,
    body       TEXT      NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
