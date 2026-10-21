package com.ledgerline.application.strategy;

import com.ledgerline.domain.exception.AccountNotFoundException;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.JournalEntry;
import com.ledgerline.domain.model.Posting;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Steps shared by every concurrency strategy. The strategies differ only in how they
 * read and write account balances; validation and journal construction are identical.
 */
final class TransferPlan {

    /**
     * Canonical lock order, matching how PostgreSQL sorts the uuid type (unsigned, byte by byte).
     * UUID.compareTo compares signed longs and disagrees for ids starting with 8-f, so it is not
     * used here. The canonical lowercase hex string sorts exactly like the unsigned bytes.
     */
    static final Comparator<UUID> LOCK_ORDER = Comparator.comparing(UUID::toString);

    private TransferPlan() {
    }

    static List<UUID> accountIdsInLockOrder(LedgerCommand command) {
        return command.accountIds().stream().sorted(LOCK_ORDER).toList();
    }

    static Map<UUID, Account> indexById(List<UUID> requiredIds, List<Account> loaded) {
        Map<UUID, Account> byId = loaded.stream().collect(Collectors.toMap(Account::getId, Function.identity()));
        for (UUID id : requiredIds) {
            if (!byId.containsKey(id)) {
                throw new AccountNotFoundException(id);
            }
        }
        return byId;
    }

    /** Validates (zero-sum per currency, currency match, leg count) BEFORE any balance is touched. */
    static JournalEntry buildEntry(LedgerCommand command, String reference, Map<UUID, Account> accounts) {
        List<JournalEntry.PostingInstruction> instructions = command.legs().stream()
                .map(leg -> new JournalEntry.PostingInstruction(accounts.get(leg.accountId()), leg.direction(), leg.amountMinor()))
                .toList();
        return command.currency() == null
                ? JournalEntry.createMultiCurrency(command.description(), reference, instructions)
                : JournalEntry.create(command.description(), command.currency(), reference, instructions);
    }

    /** Applies each posting to the in-memory account (strategies A and B). */
    static void applyToAccounts(JournalEntry entry) {
        for (Posting posting : entry.getPostings()) {
            posting.getAccount().applyPosting(posting.getDirection(), posting.getAmount(), posting.getCurrency());
        }
    }

    /** Net signed balance change per account, iterated in lock order (strategy C). */
    static Map<UUID, Long> netDeltasInLockOrder(JournalEntry entry) {
        Map<UUID, Long> deltas = new TreeMap<>(LOCK_ORDER);
        for (Posting posting : entry.getPostings()) {
            Account account = posting.getAccount();
            long delta = account.getType().calculateSignedDelta(posting.getDirection(), posting.getAmount());
            deltas.merge(account.getId(), delta, Math::addExact);
        }
        return deltas;
    }
}
