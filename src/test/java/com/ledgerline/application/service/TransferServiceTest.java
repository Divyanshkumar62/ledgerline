package com.ledgerline.application.service;

import com.ledgerline.application.dto.TransferLegRequest;
import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.application.dto.TransferResponse;
import com.ledgerline.domain.exception.AccountNotFoundException;
import com.ledgerline.domain.exception.ImbalancedJournalEntryException;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.AccountType;
import com.ledgerline.domain.model.JournalEntry;
import com.ledgerline.domain.model.PostingDirection;
import com.ledgerline.domain.repository.AccountRepository;
import com.ledgerline.domain.repository.JournalEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private JournalEntryRepository journalEntryRepository;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(accountRepository, journalEntryRepository);
    }

    @Test
    @DisplayName("Locks accounts in strict ascending UUID order to eliminate deadlocks")
    void executeTransfer_locksAccountsInAscendingUuidOrder() {
        UUID idA = UUID.fromString("10000000-0000-0000-0000-000000000000");
        UUID idB = UUID.fromString("20000000-0000-0000-0000-000000000000");

        Account accountA = new Account(idA, AccountType.ASSET, "USD", true, 5_000L);
        Account accountB = new Account(idB, AccountType.ASSET, "USD", true, 1_000L);

        // Requested with idB first, then idA
        TransferRequest request = new TransferRequest(
                "Reverse order request",
                "USD",
                "key-1",
                List.of(
                        new TransferLegRequest(idB, PostingDirection.CREDIT, 500L),
                        new TransferLegRequest(idA, PostingDirection.DEBIT, 500L)
                )
        );

        when(accountRepository.findByIdInOrderByIdAscForUpdate(List.of(idA, idB)))
                .thenReturn(List.of(accountA, accountB));
        when(journalEntryRepository.save(any(JournalEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        TransferResponse response = transferService.executeTransfer(request);

        // Assert that the repository was queried with sorted UUID order: [idA, idB]
        ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
        verify(accountRepository).findByIdInOrderByIdAscForUpdate(captor.capture());
        assertThat(captor.getValue()).containsExactly(idA, idB);

        // Assert balances updated
        assertThat(accountB.getBalance()).isEqualTo(500L); // 1000 - 500
        assertThat(accountA.getBalance()).isEqualTo(5_500L); // 5000 + 500
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("Missing account in transfer request throws AccountNotFoundException")
    void executeTransfer_missingAccountThrowsException() {
        UUID presentId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();

        Account account = new Account(presentId, AccountType.ASSET, "USD", true, 1000L);

        TransferRequest request = new TransferRequest(
                "Missing account transfer",
                "USD",
                null,
                List.of(
                        new TransferLegRequest(presentId, PostingDirection.DEBIT, 100L),
                        new TransferLegRequest(missingId, PostingDirection.CREDIT, 100L)
                )
        );

        when(accountRepository.findByIdInOrderByIdAscForUpdate(any()))
                .thenReturn(List.of(account)); // Only presentId is returned

        assertThatThrownBy(() -> transferService.executeTransfer(request))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining(missingId.toString());

        verify(journalEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Imbalanced legs throw ImbalancedJournalEntryException and roll back")
    void executeTransfer_imbalancedLegsThrowsException() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Account a1 = new Account(id1, AccountType.ASSET, "USD", true, 1000L);
        Account a2 = new Account(id2, AccountType.ASSET, "USD", true, 1000L);

        TransferRequest request = new TransferRequest(
                "Imbalanced transfer",
                "USD",
                null,
                List.of(
                        new TransferLegRequest(id1, PostingDirection.DEBIT, 500L),
                        new TransferLegRequest(id2, PostingDirection.CREDIT, 400L)
                )
        );

        when(accountRepository.findByIdInOrderByIdAscForUpdate(any()))
                .thenReturn(List.of(a1, a2));

        assertThatThrownBy(() -> transferService.executeTransfer(request))
                .isInstanceOf(ImbalancedJournalEntryException.class);

        verify(journalEntryRepository, never()).save(any());
    }
}
