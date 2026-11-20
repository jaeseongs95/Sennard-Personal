// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

/**
 * The name of the transaction manager of the settings database. It is not an autowire candidate (a bare {@code @Transactional} and Boot's auto-configuration keep the business
 * database's one), so a use case that writes the settings database names it: {@code @Transactional(SettingsTransactions.MANAGER)}.
 */
public final class SettingsTransactions {
    public static final String MANAGER = "settingsTransactionManager";

    private SettingsTransactions() {
    }
}
