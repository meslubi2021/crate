/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.analyze;

import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertThat;

import java.util.Set;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

import io.crate.expression.symbol.Literal;
import io.crate.expression.symbol.Symbol;
import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import io.crate.testing.SQLExecutor;

public class ResetAnalyzerTest extends CrateDummyClusterServiceUnitTest {

    private SQLExecutor executor;

    @Before
    public void prepare() {
        executor = SQLExecutor.builder(clusterService).build();
    }

    @Test
    public void testReset() throws Exception {
        AnalyzedResetStatement analysis = executor.analyze("RESET GLOBAL stats.enabled");
        assertThat(analysis.settingsToRemove(), contains(Literal.of("stats.enabled")));

        analysis = executor.analyze("RESET GLOBAL stats");
        assertThat(analysis.settingsToRemove(), contains(Literal.of("stats")));
    }

    @Test
    public void testResetLoggingSetting() {
        AnalyzedResetStatement analysis = executor.analyze("RESET GLOBAL \"logger.action\"");
        assertThat(analysis.settingsToRemove(), Matchers.<Set<Symbol>>is(Set.of(Literal.of("logger.action"))));
    }
}
