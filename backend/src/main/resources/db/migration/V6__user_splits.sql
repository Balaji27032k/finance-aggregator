-- A payment the user split across categories on the phone, stored as "Category|amount;Category|amount".
ALTER TABLE financial_transactions ADD COLUMN user_splits VARCHAR(700) NULL;
