/*
 * Copyright 2018 Google LLC
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google LLC nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.google.api.gax.retrying;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.api.gax.core.FakeApiClock;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.threeten.bp.Duration;

@RunWith(JUnit4.class)
public class ExponentialRetryAlgorithmTest {
  private final FakeApiClock clock = new FakeApiClock(0L);
  private final RetrySettings retrySettings =
      RetrySettings.newBuilder()
          .setMaxAttempts(6)
          .setInitialRetryDelay(Duration.ofMillis(1L))
          .setRetryDelayMultiplier(2.0)
          .setMaxRetryDelay(Duration.ofMillis(8L))
          .setInitialRpcTimeout(Duration.ofMillis(1L))
          .setJittered(true)
          .setRpcTimeoutMultiplier(2.0)
          .setMaxRpcTimeout(Duration.ofMillis(8L))
          .setTotalTimeout(Duration.ofMillis(200L))
          .build();
  private final ExponentialRetryAlgorithm algorithm =
      new ExponentialRetryAlgorithm(retrySettings, clock);

  @Test
  public void testCreateFirstAttempt() {
    TimedAttemptSettings attempt = algorithm.createFirstAttempt();

    // Checking only the most core values, to not make this test too implementation specific.
    assertEquals(0, attempt.getAttemptCount());
    assertEquals(0, attempt.getOverallAttemptCount());
    assertEquals(Duration.ZERO, attempt.getRetryDelay());
    assertEquals(Duration.ZERO, attempt.getRandomizedRetryDelay());
    assertEquals(Duration.ofMillis(1L), attempt.getRpcTimeout());
    assertEquals(Duration.ZERO, attempt.getRetryDelay());
  }

  @Test
  public void testCreateNextAttempt() {
    TimedAttemptSettings firstAttempt = algorithm.createFirstAttempt();
    TimedAttemptSettings secondAttempt = algorithm.createNextAttempt(firstAttempt);

    // Checking only the most core values, to not make this test too implementation specific.
    assertEquals(1, secondAttempt.getAttemptCount());
    assertEquals(1, secondAttempt.getOverallAttemptCount());
    assertEquals(Duration.ofMillis(1L), secondAttempt.getRetryDelay());
    assertEquals(Duration.ofMillis(1L), secondAttempt.getRandomizedRetryDelay());
    assertEquals(Duration.ofMillis(2L), secondAttempt.getRpcTimeout());

    TimedAttemptSettings thirdAttempt = algorithm.createNextAttempt(secondAttempt);
    assertEquals(2, thirdAttempt.getAttemptCount());
    assertEquals(Duration.ofMillis(2L), thirdAttempt.getRetryDelay());
    assertEquals(Duration.ofMillis(2L), thirdAttempt.getRandomizedRetryDelay());
    assertEquals(Duration.ofMillis(4L), thirdAttempt.getRpcTimeout());
  }

  @Test
  public void testJitteredRetryDelay() {
    TimedAttemptSettings firstAttempt = algorithm.createFirstAttempt();
    TimedAttemptSettings currentAttempt = algorithm.createNextAttempt(firstAttempt);

    // Validate that retry delay is growing exponentially for max iterations until maxRetryDelay.
    for (int i = 1; i < retrySettings.getMaxAttempts(); i++) {
      TimedAttemptSettings nextAttempt = algorithm.createNextAttempt(currentAttempt);
      long nextRetryDelay =
          Math.min(
              retrySettings.getMaxRetryDelay().toMillis(),
              (long) Math.pow(retrySettings.getRetryDelayMultiplier(), i));
      // Check lower bound.
      assertTrue(nextAttempt.getRandomizedRetryDelay().toMillis() >= (long) (0.8 * nextRetryDelay));
      // Check upper bound.
      assertTrue(nextAttempt.getRandomizedRetryDelay().toMillis() <= (long) (1.2 * nextRetryDelay));

      currentAttempt = nextAttempt;
    }
  }

  @Test
  public void testShouldRetryTrue() {
    TimedAttemptSettings attempt = algorithm.createFirstAttempt();
    for (int i = 0; i < 2; i++) {
      attempt = algorithm.createNextAttempt(attempt);
    }

    assertTrue(algorithm.shouldRetry(attempt));
  }

  @Test
  public void testShouldRetryFalseOnMaxAttempts() {
    TimedAttemptSettings attempt = algorithm.createFirstAttempt();
    for (int i = 0; i < 6; i++) {
      assertTrue(algorithm.shouldRetry(attempt));
      attempt = algorithm.createNextAttempt(attempt);
    }

    assertFalse(algorithm.shouldRetry(attempt));
  }

  @Test
  public void testShouldRetryFalseOnMaxTimeout() {
    TimedAttemptSettings attempt = algorithm.createFirstAttempt();
    for (int i = 0; i < 4; i++) {
      assertTrue(algorithm.shouldRetry(attempt));
      attempt = algorithm.createNextAttempt(attempt);
      clock.incrementNanoTime(Duration.ofMillis(50L).toNanos());
    }

    assertFalse(algorithm.shouldRetry(attempt));
  }
}
