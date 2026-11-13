/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

const { ManagementRestrictions } = ChromeUtils.importESModule(
  "resource://gre/modules/ManagementRestrictions.sys.mjs"
);
const { OSKeyStore } = ChromeUtils.importESModule(
  "resource://gre/modules/OSKeyStore.sys.mjs"
);
const { sinon: totpSinon } = ChromeUtils.importESModule(
  "resource://testing-common/Sinon.sys.mjs"
);

const TOTP_PREF_BRANCH = "extensions.managementRestrictions.";
const TOTP_TEST_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
let totpClock;

add_setup(function () {
  const moduleGlobal = Cu.getGlobalForObject(ManagementRestrictions);
  totpClock = totpSinon.stub(moduleGlobal.Date, "now");
  totpSinon.stub(OSKeyStore, "encrypt").callsFake(async secret => secret);
  totpSinon.stub(OSKeyStore, "decrypt").callsFake(async secret => secret);
  registerCleanupFunction(() => {
    totpSinon.restore();
    Services.prefs.deleteBranch(TOTP_PREF_BRANCH);
  });
});

async function configureTotp() {
  Services.prefs.deleteBranch(TOTP_PREF_BRANCH);
  totpClock.returns(30000);
  Assert.ok(
    await ManagementRestrictions.completeTotpSetup(
      TOTP_TEST_SECRET,
      "recovery-code",
      "287082"
    ),
    "Enroll with the RFC 4226 code for counter 1"
  );
}

add_task(async function test_unused_code_valid_until_100_seconds() {
  await configureTotp();
  totpClock.returns(159999);
  Assert.ok(
    await ManagementRestrictions.setAdminModeEnabledByUser(true, "359152"),
    "Counter 2 code remains valid 99.999 seconds after it appeared"
  );
});

add_task(async function test_code_expires_at_100_seconds() {
  await configureTotp();
  totpClock.returns(160000);
  Assert.ok(
    !(await ManagementRestrictions.setAdminModeEnabledByUser(true, "359152")),
    "Counter 2 code expires exactly 100 seconds after it appeared"
  );
  Assert.ok(!ManagementRestrictions.isAdminModeEnabled());
});

add_task(async function test_code_cannot_be_replayed() {
  await configureTotp();
  totpClock.returns(60000);
  Assert.ok(
    await ManagementRestrictions.setAdminModeEnabledByUser(true, "359152")
  );
  await ManagementRestrictions.setAdminModeEnabledByUser(false);
  totpClock.returns(150000);
  Assert.ok(
    !(await ManagementRestrictions.setAdminModeEnabledByUser(true, "359152"))
  );
  Assert.ok(!ManagementRestrictions.isAdminModeEnabled());
});

add_task(async function test_newer_code_prevents_reusing_older_codes() {
  await configureTotp();
  totpClock.returns(90000);
  Assert.ok(
    await ManagementRestrictions.setAdminModeEnabledByUser(true, "969429")
  );
  await ManagementRestrictions.setAdminModeEnabledByUser(false);
  Assert.ok(
    !(await ManagementRestrictions.setAdminModeEnabledByUser(true, "359152"))
  );
});

add_task(async function test_one_step_clock_skew_remains_supported() {
  await configureTotp();
  totpClock.returns(60000);
  Assert.ok(
    await ManagementRestrictions.setAdminModeEnabledByUser(true, "969429")
  );
});

add_task(async function test_lockout_still_applies() {
  await configureTotp();
  totpClock.returns(60000);
  for (let attempt = 0; attempt < 5; attempt++) {
    Assert.ok(
      !(await ManagementRestrictions.setAdminModeEnabledByUser(true, "000000"))
    );
  }
  Assert.ok(
    !(await ManagementRestrictions.setAdminModeEnabledByUser(true, "359152"))
  );
  Assert.ok(!ManagementRestrictions.isAdminModeEnabled());
});

add_task(function test_enrollment_keeps_standard_authenticator_period() {
  const setup = ManagementRestrictions.beginTotpSetup();
  Assert.equal(new URL(setup.otpauthURI).searchParams.get("period"), "30");
});
