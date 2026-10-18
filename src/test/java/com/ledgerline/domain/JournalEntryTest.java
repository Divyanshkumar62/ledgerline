package com.ledgerline.domain;

import com.ledgerline.domain.exception.CurrencyMismatchException;
import com.ledgerline.domain.exception.ImbalancedJournalEntryException;
import com.ledgerline.domain.exception.InsufficientFundsException;
import com.ledgerline.domain.exception.InvalidJournalEntryException;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.AccountType;
import com.ledgerline.domain.model.JournalEntry;
import com.ledgerline.domain.model.PostingDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    @Test
    @DisplayName("A balanced two-leg transfer successfully creates a JournalEntry and Postings")
    void balancedTwoLegTransfer_succeeds() {
        Account source = new Account(UUID.randomUUID(), AccountType.ASSET, "USD", true, 10_000L);
        Account target = new Account(UUID.randomUUID(), AccountType.ASSET, "USD", true, 0L);

        JournalEntry entry = JournalEntry.create(
                "Settlement payout",
                "USD",
                "idemp-001",
                List.of(
                        new JournalEntry.PostingInstruction(target, PostingDirection.DEBIT, 2_500L),
                        new JournalEntry.PostingInstruction(source, PostingDirection.CREDIT, 2_500L)
                )
        );

        assertThat(entry).isNotNull();
        assertThat(entry.getPostings()).hasSize(2);
        assertThat(entry.getDescription()).isEqualTo("Settlement payout");
        assertThat(entry.getCurrency()).isEqualTo("USD");

        long totalDebits = entry.getPostings().stream()
                .filter(p -> p.getDirection() == PostingDirection.DEBIT)
                .mapToLong(p -> p.getAmount())
                .sum();
        long totalCredits = entry.getPostings().stream()
                .filter(p -> p.getDirection() == PostingDirection.CREDIT)
                .mapToLong(p -> p.getAmount())
                .sum();

        assertThat(totalDebits).isEqualTo(2_500L);
        assertThat(totalCredits).isEqualTo(2_500L);
        assertThat(totalDebits).isEqualTo(totalCredits);
    }

    @Test
    @DisplayName("An imbalanced transfer is strictly rejected with ImbalancedJournalEntryException")
    void imbalancedTransfer_throwsException() {
        Account source = new Account(UUID.randomUUID(), AccountType.ASSET, "USD", true, 10_000L);
        Account target = new Account(UUID.randomUUID(), AccountType.ASSET, "USD", true, 0L);

        assertThatThrownBy(() -> JournalEntry.create(
                "Imbalanced wire",
                "USD",
                null,
                List.of(
                        new JournalEntry.PostingInstruction(target, PostingDirection.DEBIT, 5_000L),
                        new JournalEntry.PostingInstruction(source, PostingDirection.CREDIT, 4_500L)
                )
        ))
        .isInstanceOf(ImbalancedJournalEntryException.class)
        .hasMessageContaining("Total debits (5000) do not equal total credits (4500)");
    }

    @Test
    @DisplayName("A balanced multi-leg (split) transaction successfully creates all postings")
    void multiLegBalancedTransfer_succeeds() {
        Account payer = new Account(UUID.randomUUID(), AccountType.ASSET, "USD", true, 100_000L);
        Account merchant = new Account(UUID.randomUUID(), AccountType.ASSET, "USD", true, 0L);
        Account platformFee = new Account(UUID.randomUUID(), AccountType.REVENUE, "USD", false, 0L);

        // Payer pays 10,000 (CREDIT ASSET -10,000)
        // Merchant receives 9,700 (DEBIT ASSET +9,700)
        // Platform collects fee 300 (CREDIT REVENUE +300)
        // In double-entry: Total Debits = 9,700 (Merchant) + 300 (Fee) = 10,000? Wait:
        // Payer: Credit 10,000
        // Merchant: Debit 9,700
        // Platform Fee: Credit 300? Wait: Credit 10,000 + Credit 300 = Credit 10,300 vs Debit 9,700 -> imbalanced!
        // Correct double entry:
        // Customer Bank Account (Asset): CREDIT 10,000 (money leaves customer)
        // Merchant Bank Account (Asset): DEBIT 9,700 (money arrives at merchant)
        // Platform Bank Account (Asset): DEBIT 300 (money arrives at platform) -> Total Debits = 9700 + 300 = 10,000 == Total Credits 10,000!
        JournalEntry entry = JournalEntry.create(
                "Split payment with platform fee",
                "USD",
                "idemp-split-1",
                List.of(
                        new JournalEntry.PostingInstruction(payer, PostingDirection.CREDIT, 10_000L),
                        new JournalEntry.PostingInstruction(merchant, PostingDirection.DEBIT, 9_700L),
                        new JournalEntry.PostingInstruction(platformFee, PostingDirection.DEBIT, 300L)
                )
        );

        assertThat(entry.getPostings()).hasSize(3);
    }

    @Test
    @DisplayName("A single-leg transfer violates double-entry rules and throws InvalidJournalEntryException")
    void singleLegTransfer_isRejected() {
        Account single = new Account(UUID.randomUUID(), AccountType.ASSET, "USD", true, 1_000L);

        assertThatThrownBy(() -> JournalEntry.create(
                "Orphan leg",
                "USD",
                null,
                List.of(new JournalEntry.PostingInstruction(single, PostingDirection.DEBIT, 1_000L))
        ))
        .isInstanceOf(InvalidJournalEntryException.class)
        .hasMessageContaining("at least 2 postings");
    }

    @Test
    @DisplayName("Currency mismatch between entry and account throws CurrencyMismatchException")
    void currencyMismatch_isRejected() {
        Account usdAccount = new Account(UUID.randomUUID(), AccountType.ASSET, "USD", true, 5_000L);
        Account eurAccount = new Account(UUID.randomUUID(), AccountType.ASSET, "EUR", true, 5_000L);

        assertThatThrownBy(() -> JournalEntry.create(
                "Cross currency transfer attempt",
                "USD",
                null,
                List.of(
                        new JournalEntry.PostingInstruction(usdAccount, PostingDirection.DEBIT, 1_000L),
                        new JournalEntry.PostingInstruction(eurAccount, PostingDirection.CREDIT, 1_000L)
                )
        ))
        .isInstanceOf(CurrencyMismatchException.class)
        .hasMessageContaining("does not match journal entry currency (USD)");
    }

    @Test
    @DisplayName("Applying a deduction beyond balance throws InsufficientFundsException when disallowNegativeBalance is enabled")
    void disallowNegativeBalance_enforced() {
        Account account = new Account(UUID.randomUUID(), AccountType.ASSET, "USD", true, 500L);

        assertThatThrownBy(() -> account.applyPosting(PostingDirection.CREDIT, 600L, "USD"))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("has insufficient funds: current balance is 500");

        assertThat(account.getBalance()).isEqualTo(500L); // Unchanged
    }

    @Test
    @DisplayName("Applying a deduction beyond balance succeeds when disallowNegativeBalance is disabled (e.g. overdraft/clearing)")
    void allowNegativeBalance_permitsOverdraft() {
        Account account = new Account(UUID.randomUUID(), AccountType.ASSET, "USD", false, 500L);

        account.applyPosting(PostingDirection.CREDIT, 750L, "USD");

        assertThat(account.getBalance()).isEqualTo(-250L);
    }
}
