-- Receipts may now come from the Ethereum ledger. For those rows,
-- fabric_transaction_id holds the transaction hash, channel_name the chain ID,
-- and chaincode_name the contract address.
ALTER TABLE fabric_transactions DROP CONSTRAINT ck_fabric_transactions_source;
ALTER TABLE fabric_transactions ADD CONSTRAINT ck_fabric_transactions_source
    CHECK (source IN ('fabric', 'legacy', 'ethereum'));
