package com.ledgerline.application.service;

import com.ledgerline.application.strategy.LedgerCommand;
import com.ledgerline.domain.exception.AccountFrozenException;
import com.ledgerline.domain.exception.InvalidRequestException;
import com.ledgerline.domain.model.AccountType;
import com.ledgerline.domain.model.PostingDirection;
import com.ledgerline.domain.model.StripeLayout;
import com.ledgerline.domain.repository.StripeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ADR-014 routing rules, without a database. */
class StripeRouterTest {

    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID PARENT = UUID.randomUUID();
    private static final List<UUID> STRIPES = IntStream.range(0, 8).mapToObj(i -> UUID.randomUUID()).toList();

    private final StripeRepository repository = mock(StripeRepository.class);
    private final StripeRouter router = new StripeRouter(repository);

    private static LedgerCommand payParent(PostingDirection parentDirection) {
        PostingDirection other = parentDirection == PostingDirection.DEBIT ? PostingDirection.CREDIT : PostingDirection.DEBIT;
        return new LedgerCommand("fee", "USD", List.of(
                new LedgerCommand.Leg(SOURCE, other, 100),
                new LedgerCommand.Leg(PARENT, parentDirection, 100)));
    }

    private void parentIs(AccountType type, boolean frozen) {
        when(repository.findLayouts(any())).thenReturn(Map.of(PARENT, new StripeLayout(PARENT, type, frozen, null, STRIPES)));
    }

    @Test
    @DisplayName("No striped account in the transfer → the command is returned untouched")
    void plainAccountsPassThrough() {
        LedgerCommand command = payParent(PostingDirection.DEBIT);
        when(repository.findLayouts(any())).thenReturn(Map.of());
        assertThat(router.route(command, "c:k")).isSameAs(command);
    }

    @Test
    @DisplayName("A leg that raises a striped parent's balance is moved to one of its stripes; the other leg is untouched")
    void creditToParentGoesToAStripe() {
        parentIs(AccountType.REVENUE, false); // credit-normal: a CREDIT raises it

        LedgerCommand routed = router.route(payParent(PostingDirection.CREDIT), "client:key-1");

        assertThat(routed.legs().get(0).accountId()).isEqualTo(SOURCE);
        assertThat(STRIPES).contains(routed.legs().get(1).accountId());
        assertThat(routed.legs().get(1).amountMinor()).isEqualTo(100);
    }

    @Test
    @DisplayName("Same idempotency reference → same stripe, every time (a retry lands where the original did)")
    void routingIsDeterministicPerReference() {
        parentIs(AccountType.ASSET, false);
        UUID first = router.route(payParent(PostingDirection.DEBIT), "client:key-42").legs().get(1).accountId();
        for (int i = 0; i < 20; i++) {
            assertThat(router.route(payParent(PostingDirection.DEBIT), "client:key-42").legs().get(1).accountId()).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("Hashing spreads 8,000 distinct keys over 8 stripes within ±15% of an even share")
    void hashingSpreadsKeysEvenly() {
        int[] counts = new int[8];
        for (int i = 0; i < 8_000; i++) {
            counts[StripeRouter.stripeIndex("client:key-" + i, 8)]++;
        }
        assertThat(java.util.Arrays.stream(counts).boxed().toList()).allSatisfy(c -> assertThat(c).isBetween(850, 1150));
    }

    @Test
    @DisplayName("A leg that would LOWER a striped parent's balance is rejected: it has no balance check to protect it")
    void debitOfStripedParentIsRejected() {
        parentIs(AccountType.ASSET, false); // debit-normal: a CREDIT lowers it
        assertThatThrownBy(() -> router.route(payParent(PostingDirection.CREDIT), "c:k"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("receive-only");
    }

    @Test
    @DisplayName("Naming a stripe directly is rejected, so the receive-only rule cannot be bypassed through it")
    void stripeCannotBeNamedDirectly() {
        UUID stripe = STRIPES.get(3);
        when(repository.findLayouts(any())).thenReturn(Map.of(stripe, new StripeLayout(stripe, AccountType.ASSET, false, PARENT, List.of())));
        LedgerCommand command = new LedgerCommand("x", "USD", List.of(
                new LedgerCommand.Leg(stripe, PostingDirection.CREDIT, 100),
                new LedgerCommand.Leg(SOURCE, PostingDirection.DEBIT, 100)));
        assertThatThrownBy(() -> router.route(command, "c:k"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("is a stripe of account " + PARENT);
    }

    @Test
    @DisplayName("A frozen striped parent takes part in no transfer")
    void frozenParentIsRejected() {
        parentIs(AccountType.ASSET, true);
        assertThatThrownBy(() -> router.route(payParent(PostingDirection.DEBIT), "c:k")).isInstanceOf(AccountFrozenException.class);
    }

    @Test
    @DisplayName("Two legs on the same striped parent use the same stripe, even on the random (internal) path")
    void oneStripePerParentPerTransfer() {
        parentIs(AccountType.ASSET, false);
        LedgerCommand command = new LedgerCommand("x", "USD", List.of(
                new LedgerCommand.Leg(SOURCE, PostingDirection.CREDIT, 300),
                new LedgerCommand.Leg(PARENT, PostingDirection.DEBIT, 100),
                new LedgerCommand.Leg(PARENT, PostingDirection.DEBIT, 200)));
        for (int i = 0; i < 20; i++) {
            LedgerCommand routed = router.route(command, null);
            assertThat(routed.legs().get(1).accountId()).isEqualTo(routed.legs().get(2).accountId());
        }
    }
}
