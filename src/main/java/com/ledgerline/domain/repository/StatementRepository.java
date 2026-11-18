package com.ledgerline.domain.repository;

import com.ledgerline.domain.model.PostingDirection;
import com.ledgerline.domain.model.StatementLine;

import java.util.List;
import java.util.UUID;

public interface StatementRepository {

    /**
     * Newest first, keyset-paginated on posting seq.
     *
     * @param accountIds     the account, or for a striped parent its stripes (ADR-014), merged by seq
     * @param beforeSeq      only postings with seq < beforeSeq; null for the newest page
     * @param currentBalance the account balance read in the same snapshot, used to derive balanceAfter
     */
    List<StatementLine> page(List<UUID> accountIds, PostingDirection normalBalance, long currentBalance, Long beforeSeq, int limit);
}
