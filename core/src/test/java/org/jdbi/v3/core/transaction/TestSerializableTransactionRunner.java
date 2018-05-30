/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jdbi.v3.core.transaction;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.rule.H2DatabaseRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class TestSerializableTransactionRunner {
    private static final int RETRIES = 5;

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();
    @Mock
    private Consumer<List<Exception>> onFailure;

    @Rule
    public H2DatabaseRule dbRule = new H2DatabaseRule();

    @Before
    public void setUp() throws Exception {
        dbRule.getJdbi().setTransactionHandler(new SerializableTransactionRunner());
        dbRule.getJdbi().getConfig(SerializableTransactionRunner.Configuration.class)
            .setMaxRetries(RETRIES)
            .setOnFailure(onFailure);
    }

    @Test
    public void testEventuallyFails() {
        final AtomicInteger attempts = new AtomicInteger(0);
        Handle handle = dbRule.getJdbi().open();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> handle.inTransaction(TransactionIsolationLevel.SERIALIZABLE,
                        conn -> {
                            attempts.incrementAndGet();
                            throw new SQLException("serialization", "40001");
                        }))
                .satisfies(e -> assertThat(e.getSQLState()).isEqualTo("40001"))
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(RETRIES));
        assertThat(attempts.get()).isEqualTo(1 + RETRIES);
    }

    @Test
    public void testEventuallySucceeds() throws Exception {
        final AtomicInteger remaining = new AtomicInteger(RETRIES / 2);
        Handle handle = dbRule.getJdbi().open();

        handle.inTransaction(TransactionIsolationLevel.SERIALIZABLE, conn -> {
            if (remaining.decrementAndGet() == 0) {
                return null;
            }
            throw new SQLException("serialization", "40001");
        });

        assertThat(remaining.get()).isZero();
    }

    @Test
    public void testNonsenseRetryCount() {
        assertThatThrownBy(() -> dbRule.getJdbi().configure(SerializableTransactionRunner.Configuration.class, config -> config.setMaxRetries(-1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Set a number >= 0");
    }

    @Test
    public void testOnFailureCallback() throws SQLException {
        AtomicInteger remaining = new AtomicInteger(4);
        AtomicInteger expectedExceptions = new AtomicInteger(1);

        doAnswer(invocation -> {
            assertThat((List<Exception>) invocation.getArgument(0))
                .describedAs("should be called with " + expectedExceptions.get() + " exceptions")
                .hasSize(expectedExceptions.getAndIncrement());
            return null;
        }).when(onFailure).accept(anyList());

        dbRule.getJdbi().open().inTransaction(TransactionIsolationLevel.SERIALIZABLE, conn -> {
            if (remaining.decrementAndGet() == 0) {
                return null;
            }
            throw new SQLException("serialization", "40001");
        });

        verify(onFailure, times(3)).accept(anyList());
        assertThat(expectedExceptions.get()).isEqualTo(4);
    }
}
