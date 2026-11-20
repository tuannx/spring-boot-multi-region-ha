package com.multiregion.router;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryBudgetTest {

    @Test
    void minimumConcurrencyAppliesWhenTrafficIsLow() {
        RetryBudget budget = new RetryBudget(20, 3);
        for (int i = 0; i < 10; i++) {
            budget.requestStarted();
        }

        assertEquals(3, budget.allowedRetries());
        assertTrue(budget.tryAcquireRetry());
        assertTrue(budget.tryAcquireRetry());
        assertTrue(budget.tryAcquireRetry());
        assertFalse(budget.tryAcquireRetry());
    }

    @Test
    void percentageAppliesWhenTrafficIsHigh() {
        RetryBudget budget = new RetryBudget(20, 3);
        for (int i = 0; i < 100; i++) {
            budget.requestStarted();
        }

        assertEquals(20, budget.allowedRetries());
    }

    @Test
    void releasedRetriesFreeTheBudget() {
        RetryBudget budget = new RetryBudget(0, 1);
        budget.requestStarted();
        assertTrue(budget.tryAcquireRetry());
        assertFalse(budget.tryAcquireRetry());

        budget.releaseRetries(1);

        assertTrue(budget.tryAcquireRetry());
    }
}
