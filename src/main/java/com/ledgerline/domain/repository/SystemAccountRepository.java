package com.ledgerline.domain.repository;

import com.ledgerline.domain.model.AccountType;

import java.util.UUID;

/** Ledger-internal accounts, one per (purpose, currency), created on first use. */
public interface SystemAccountRepository {

    String FX_CLEARING = "FX_CLEARING";
    String ADJUSTMENT = "ADJUSTMENT";

    /** Safe under concurrency: two transactions asking for the same account get the same one. */
    UUID findOrCreate(String purpose, String currency, AccountType type);
}
