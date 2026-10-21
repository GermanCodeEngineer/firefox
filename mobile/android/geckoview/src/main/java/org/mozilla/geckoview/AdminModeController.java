/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.mozilla.geckoview;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import org.mozilla.gecko.EventDispatcher;
import org.mozilla.gecko.util.GeckoBundle;

/** Provides authenticated Admin Mode operations to GeckoView embedders. */
@ExperimentalGeckoViewApi
public final class AdminModeController {
  private static final String GET_STATE = "GeckoView:ManagementRestrictions:GetState";
  private static final String SET_ENABLED = "GeckoView:ManagementRestrictions:SetEnabled";
  private static final String BEGIN_TOTP_SETUP = "GeckoView:ManagementRestrictions:BeginTotpSetup";
  private static final String COMPLETE_TOTP_SETUP = "GeckoView:ManagementRestrictions:CompleteTotpSetup";
  private static final String RECOVER = "GeckoView:ManagementRestrictions:Recover";

  private AdminModeController() {}

  /** Returns the enabled state and whether authenticator protection is configured. */
  @AnyThread
  public static @NonNull GeckoResult<GeckoBundle> getState() {
    return EventDispatcher.getInstance().queryBundle(GET_STATE);
  }

  /** Changes Admin Mode state, validating the authenticator code when enabling. */
  @AnyThread
  public static @NonNull GeckoResult<Boolean> setEnabled(
      final boolean enabled, final @NonNull String code) {
    final GeckoBundle data = new GeckoBundle(2);
    data.putBoolean("enabled", enabled);
    data.putString("code", code);
    return EventDispatcher.getInstance().queryBoolean(SET_ENABLED, data);
  }

  /** Creates authenticator setup data. */
  @AnyThread
  public static @NonNull GeckoResult<GeckoBundle> beginTotpSetup() {
    return EventDispatcher.getInstance().queryBundle(BEGIN_TOTP_SETUP);
  }

  /** Saves authenticator setup after verifying the first code. */
  @AnyThread
  public static @NonNull GeckoResult<Boolean> completeTotpSetup(
      final @NonNull String secret,
      final @NonNull String recoveryCode,
      final @NonNull String code) {
    final GeckoBundle data = new GeckoBundle(3);
    data.putString("secret", secret);
    data.putString("recoveryCode", recoveryCode);
    data.putString("code", code);
    return EventDispatcher.getInstance().queryBoolean(COMPLETE_TOTP_SETUP, data);
  }

  /** Verifies and consumes the emergency recovery code after explicit confirmation. */
  @AnyThread
  public static @NonNull GeckoResult<Boolean> recover(
      final @NonNull String code, final boolean confirmed) {
    final GeckoBundle data = new GeckoBundle(2);
    data.putString("code", code);
    data.putBoolean("confirmed", confirmed);
    return EventDispatcher.getInstance().queryBoolean(RECOVER, data);
  }
}
