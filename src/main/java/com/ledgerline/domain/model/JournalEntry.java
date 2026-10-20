package com.ledgerline.domain.model;

import com.ledgerline.domain.exception.CurrencyMismatchException;
import com.ledgerline.domain.exception.ImbalancedJournalEntryException;
import com.ledgerline.domain.exception.InvalidJournalEntryException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

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

    /** Null for a multi-currency (FX) entry. */
    @Column(length = 3)
    private String currency;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Posting> postings = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = LedgerTime.now();

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
     * Single-currency entry: every account must be in {@code currency}, and debits must equal credits.
     */
    public static JournalEntry create(String description, String currency, String idempotencyReference, List<PostingInstruction> instructions) {
        Objects.requireNonNull(currency, "Currency must not be null");
        String normalizedCurrency = currency.trim().toUpperCase();
        Objects.requireNonNull(instructions, "Posting instructions must not be null");
        for (PostingInstruction instruction : instructions) {
            if (!instruction.account().getCurrency().equalsIgnoreCase(normalizedCurrency)) {
                throw new CurrencyMismatchException(String.format(
                        "Account %s currency (%s) does not match journal entry currency (%s)",
                        instruction.account().getId(), instruction.account().getCurrency(), normalizedCurrency));
            }
        }
        return createMultiCurrency(description, idempotencyReference, instructions);
    }

    /**
     * Entry that may span currencies (ADR-012). Each posting is in its account's currency, and the
     * zero-sum invariant holds PER CURRENCY: debits equal credits within every currency separately.
     * Value moves between currencies only through FX clearing accounts, never by netting USD against EUR.
     */
    public static JournalEntry createMultiCurrency(String description, String idempotencyReference, List<PostingInstruction> instructions) {
        Objects.requireNonNull(description, "Description must not be null");
        Objects.requireNonNull(instructions, "Posting instructions must not be null");
        if (instructions.size() < 2) {
            throw new InvalidJournalEntryException("A journal entry must contain at least 2 postings (legs)");
        }

        Map<String, long[]> debitsAndCreditsByCurrency = new TreeMap<>();
        for (PostingInstruction instruction : instructions) {
            long[] totals = debitsAndCreditsByCurrency.computeIfAbsent(instruction.account().getCurrency(), c -> new long[2]);
            int side = instruction.direction() == PostingDirection.DEBIT ? 0 : 1;
            totals[side] = Math.addExact(totals[side], instruction.amountMinor());
        }
        debitsAndCreditsByCurrency.forEach((ccy, totals) -> {
            if (totals[0] != totals[1]) {
                throw new ImbalancedJournalEntryException(totals[0], totals[1], ccy);
            }
        });

        JournalEntry entry = new JournalEntry();
        entry.description = description;
        // An entry in one currency records it; an FX entry spans several and has none.
        entry.currency = debitsAndCreditsByCurrency.size() == 1 ? debitsAndCreditsByCurrency.keySet().iterator().next() : null;
        entry.idempotencyReference = idempotencyReference;
        entry.createdAt = LedgerTime.now();

        for (PostingInstruction instruction : instructions) {
            entry.postings.add(new Posting(entry, instruction.account(), instruction.direction(),
                    instruction.amountMinor(), instruction.account().getCurrency()));
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
