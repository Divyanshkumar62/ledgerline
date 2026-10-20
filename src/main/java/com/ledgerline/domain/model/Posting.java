package com.ledgerline.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An individual financial leg (Posting) belonging to a JournalEntry.
 * Postings are strictly immutable and append-only.
 */
@Entity
@Table(name = "postings")
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private JournalEntry journalEntry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PostingDirection direction;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = LedgerTime.now();

    protected Posting() {
        // Required by JPA
    }

    public Posting(JournalEntry journalEntry, Account account, PostingDirection direction, long amount, String currency) {
        this.journalEntry = Objects.requireNonNull(journalEntry, "Journal entry must not be null");
        this.account = Objects.requireNonNull(account, "Account must not be null");
        this.direction = Objects.requireNonNull(direction, "Direction must not be null");
        if (amount <= 0) {
            throw new IllegalArgumentException("Posting amount must be strictly greater than 0: " + amount);
        }
        this.amount = amount;
        Objects.requireNonNull(currency, "Currency must not be null");
        this.currency = currency.trim().toUpperCase();
        this.createdAt = LedgerTime.now();
    }

    public UUID getId() {
        return id;
    }

    public JournalEntry getJournalEntry() {
        return journalEntry;
    }

    public Account getAccount() {
        return account;
    }

    public PostingDirection getDirection() {
        return direction;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Posting posting)) return false;
        return Objects.equals(id, posting.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
