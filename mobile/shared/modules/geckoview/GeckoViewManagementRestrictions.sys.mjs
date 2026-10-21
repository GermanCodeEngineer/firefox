/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

const { ManagementRestrictions } = ChromeUtils.importESModule(
  "resource://gre/modules/ManagementRestrictions.sys.mjs"
);

export const GeckoViewManagementRestrictions = {
  async onEvent(event, data, callback) {
    try {
      switch (event) {
        case "GeckoView:ManagementRestrictions:GetState":
          callback.onSuccess({
            enabled: ManagementRestrictions.isAdminModeEnabled(),
            totpConfigured: ManagementRestrictions.isTotpConfigured(),
          });
          break;
        case "GeckoView:ManagementRestrictions:SetEnabled":
          await ManagementRestrictions.setAdminModeEnabledByUser(
            data.enabled,
            data.code || ""
          );
          callback.onSuccess(
            ManagementRestrictions.isAdminModeEnabled() === data.enabled
          );
          break;
        case "GeckoView:ManagementRestrictions:BeginTotpSetup":
          callback.onSuccess(ManagementRestrictions.beginTotpSetup());
          break;
        case "GeckoView:ManagementRestrictions:CompleteTotpSetup":
          callback.onSuccess(
            await ManagementRestrictions.completeTotpSetup(
              data.secret,
              data.recoveryCode,
              data.code
            )
          );
          break;
        case "GeckoView:ManagementRestrictions:Recover":
          callback.onSuccess(
            data.confirmed &&
              (await ManagementRestrictions.recoverAdminMode(data.code))
          );
          break;
      }
    } catch (error) {
      callback.onError(error.message);
    }
  },
};
