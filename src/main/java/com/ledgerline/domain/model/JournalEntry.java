package com.ledgerline.domain.model;

import com.ledgerline.domain.exception.CurrencyMismatchException;
import com.ledgerline.domain.exception.ImbalancedJournalEntryException;
import com.ledgerline.domain.exception.InvalidJournalEntryException;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.*;

/**
 * JournalEntry represents an atomic financial transaction containing 2 or more postings.
 * Enforces the zero-sum balance invariant: Sum(Debits) == Sum(Credits).
 */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_reference")
    private String idempotencyReference;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 3)
    private String currency;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Posting> postings = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected JournalEntry() {
        // Required by JPA
    }

    public record PostingInstruction(Account account, PostingDirection direction, long amountMinor) {
        public PostingInstruction {
            Objects.requireNonNull(account, "Account must not be null");
            Objects.requireNonNull(direction, "Posting direction must not be null");
            if (amountMinor <= 0) {
                throw new IllegalArgumentException("Posting amount must be strictly greater than zero");
            }
        }
    }

    /**
     * Factory method creating a validated, balanced JournalEntry.
     * Enforces the zero-sum invariant and single currency consistency at the domain layer.
     */
    public static JournalEntry create(String description, String currency, String idempotencyReference, List<PostingInstruction> instructions) {
        Objects.requireNonNull(description, "Description must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");
        Objects.requireNonNull(instructions, "Posting instructions must not be null");

        String normalizedCurrency = currency.trim().toUpperCase();

        if (instructions.size() < 2) {
            throw new InvalidJournalEntryException("A journal entry must contain at least 2 postings (legs)");
        }

        long totalDebits = 0L;
        long totalCredits = 0L;

        for (PostingInstruction instruction : instructions) {
            if (!instruction.account().getCurrency().equalsIgnoreCase(normalizedCurrency)) {
                throw new CurrencyMismatchException(String.format(
                        "Account %s currency (%s) does not match journal entry currency (%s)",
                        instruction.account().getId(), instruction.account().getCurrency(), normalizedCurrency));
            }

            if (instruction.direction() == PostingDirection.DEBIT) {
                totalDebits = Math.addExact(totalDebits, instruction.amountMinor());
            } else {
                totalCredits = Math.addExact(totalCredits, instruction.amountMinor());
            }
        }

        if (totalDebits != totalCredits) {
            throw new ImbalancedJournalEntryException(totalDebits, totalCredits);
        }

        JournalEntry entry = new JournalEntry();
        entry.description = description;
        entry.currency = normalizedCurrency;
        entry.idempotencyReference = idempotencyReference;
        entry.createdAt = Instant.now();

        for (PostingInstruction instruction : instructions) {
            Posting posting = new Posting(
                    entry,
                    instruction.account(),
                    instruction.direction(),
                    instruction.amountMinor(),
                    normalizedCurrency
            );
            entry.postings.add(posting);
        }

        return entry;
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyReference() {
        return idempotencyReference;
    }

    public String getDescription() {
        return description;
    }

    public String getCurrency() {
        return currency;
    }

    public List<Posting> getPostings() {
        return Collections.unmodifiableList(postings);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JournalEntry that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
