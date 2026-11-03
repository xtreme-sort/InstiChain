ALTER TABLE share_links ADD COLUMN client_request_id UUID;
CREATE UNIQUE INDEX uq_share_links_owner_request
    ON share_links(owner_id, client_request_id)
    WHERE client_request_id IS NOT NULL;
