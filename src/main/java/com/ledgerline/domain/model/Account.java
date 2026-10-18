package com.ledgerline.domain.model;

import com.ledgerline.domain.exception.CurrencyMismatchException;
import com.ledgerline.domain.exception.InsufficientFundsException;
import jakarta.persistence.*;

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

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Account() {
        // Required by JPA
    }

    public Account(AccountType type, String currency, boolean disallowNegativeBalance) {
        this.type = Objects.requireNonNull(type, "Account type must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");
        this.currency = currency.trim().toUpperCase();
        this.disallowNegativeBalance = disallowNegativeBalance;
        this.balance = 0L;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Account(UUID id, AccountType type, String currency, boolean disallowNegativeBalance, long initialBalance) {
        this.id = id;
        this.type = Objects.requireNonNull(type, "Account type must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");
        this.currency = currency.trim().toUpperCase();
        this.disallowNegativeBalance = disallowNegativeBalance;
        this.balance = initialBalance;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Updates the account's projected balance according to the posting direction and account type.
     * Enforces non-negative balance constraints inside the same transaction.
     */
    public void applyPosting(PostingDirection direction, long amountMinor, String postingCurrency) {
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
        this.updatedAt = Instant.now();
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
