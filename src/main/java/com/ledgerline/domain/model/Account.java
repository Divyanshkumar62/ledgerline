package com.ledgerline.domain.model;

import com.ledgerline.domain.exception.AccountFrozenException;
import com.ledgerline.domain.exception.CurrencyMismatchException;
import com.ledgerline.domain.exception.InsufficientFundsException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Account entity representing an individual ledger account.
 * Note: 'balance' is a cached projection of all committed postings.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountType type;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "disallow_negative_balance", nullable = false)
    private boolean disallowNegativeBalance = true;

    @Column(nullable = false)
    private long balance = 0L;

    /** Set by the audit job on detected drift (ADR-008) or by an admin. A frozen account takes part in no transfer. */
    @Column(nullable = false)
    private boolean frozen;

    /** The API client that owns this account (ADR-011). Clients can only see and move their own accounts. */
    @Column(name = "owner_client_id", nullable = false, length = 64, updatable = false)
    private String ownerClientId = SYSTEM_OWNER;

    /** Non-null only for ledger-internal accounts (FX clearing, adjustments): one per purpose and currency. */
    @Column(name = "system_purpose", length = 32, updatable = false)
    private String systemPurpose;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = LedgerTime.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = LedgerTime.now();

    protected Account() {
        // Required by JPA
    }

    /** Owner of ledger-internal accounts, and of accounts created without an owner (internal callers, tests). */
    public static final String SYSTEM_OWNER = "system";

    public Account(AccountType type, String currency, boolean disallowNegativeBalance) {
        this(type, currency, disallowNegativeBalance, SYSTEM_OWNER);
    }

    public Account(AccountType type, String currency, boolean disallowNegativeBalance, String ownerClientId) {
        this.ownerClientId = Objects.requireNonNull(ownerClientId, "Owner must not be null");
        this.type = Objects.requireNonNull(type, "Account type must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");
        this.currency = currency.trim().toUpperCase();
        this.disallowNegativeBalance = disallowNegativeBalance;
        this.balance = 0L;
        this.createdAt = LedgerTime.now();
        this.updatedAt = LedgerTime.now();
    }

    /**
     * In-memory construction for unit tests only. Persisted accounts start at zero and are funded
     * through journal entries; setting a balance directly would bypass the postings it is projected from.
     */
    public Account(UUID id, AccountType type, String currency, boolean disallowNegativeBalance, long initialBalance) {
        this.id = id;
        this.type = Objects.requireNonNull(type, "Account type must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");
        this.currency = currency.trim().toUpperCase();
        this.disallowNegativeBalance = disallowNegativeBalance;
        this.balance = initialBalance;
        this.createdAt = LedgerTime.now();
        this.updatedAt = LedgerTime.now();
    }

    /**
     * Updates the account's projected balance according to the posting direction and account type.
     * Enforces non-negative balance constraints inside the same transaction.
     */
    public void applyPosting(PostingDirection direction, long amountMinor, String postingCurrency) {
        assertNotFrozen();
        if (!this.currency.equalsIgnoreCase(postingCurrency)) {
            throw new CurrencyMismatchException(String.format(
                    "Cannot apply %s posting to account %s (%s)",
                    postingCurrency, this.id, this.currency));
        }

        long signedDelta = this.type.calculateSignedDelta(direction, amountMinor);
        long newBalance = Math.addExact(this.balance, signedDelta);

        if (this.disallowNegativeBalance && newBalance < 0) {
            throw new InsufficientFundsException(this.id, this.balance, signedDelta);
        }

        this.balance = newBalance;
        this.updatedAt = LedgerTime.now();
    }

    /** Admin action (ADR-013). */
    public void freeze() {
        this.frozen = true;
        this.updatedAt = LedgerTime.now();
    }

    /** Admin action (ADR-013). The caller must have verified that the account audits clean. */
    public void unfreeze() {
        this.frozen = false;
        this.updatedAt = LedgerTime.now();
    }

    /**
     * Admin repair (ADR-013): the postings are the truth and the cached balance was wrong, so the cache is
     * rebuilt from them. Deliberately bypasses applyPosting: no money moves, only the cache is corrected.
     */
    public void resetCachedBalance(long balanceFromPostings) {
        this.balance = balanceFromPostings;
        this.updatedAt = LedgerTime.now();
    }

    public boolean isOwnedBy(String clientId) {
        return ownerClientId.equals(clientId);
    }

    public String getOwnerClientId() {
        return ownerClientId;
    }

    public String getSystemPurpose() {
        return systemPurpose;
    }

    public void assertNotFrozen() {
        if (frozen) {
            throw new AccountFrozenException(id);
        }
    }

    public boolean isFrozen() {
        return frozen;
    }

    public UUID getId() {
        return id;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isDisallowNegativeBalance() {
        return disallowNegativeBalance;
    }

    public long getBalance() {
        return balance;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Account account)) return false;
        return Objects.equals(id, account.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
