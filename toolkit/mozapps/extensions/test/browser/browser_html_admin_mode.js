"use strict";

const ADMIN_MODE_PREF = "extensions.managementRestrictions.adminMode";
const CONTROLLER_ID_PREF = "extensions.managementRestrictions.controllerId";

add_task(async function test_admin_mode_toggle_and_controls() {
  Services.prefs.setBoolPref(ADMIN_MODE_PREF, true);

  const controller = ExtensionTestUtils.loadExtension({
    manifest: {
      permissions: ["managementRestrictions"],
    },
    background() {
      browser.management.onAdminModeRequest.addListener(({ requestId }) => {
        browser.test.sendMessage("authorization-requested");
        browser.management.respondToAdminModeRequest(requestId, true);
      });
    },
  });
  const target = ExtensionTestUtils.loadExtension({
    manifest: {
      name: "Admin Mode target",
    },
    useAddonManager: "temporary",
  });

  registerCleanupFunction(async () => {
    Services.prefs.clearUserPref(ADMIN_MODE_PREF);
    Services.prefs.clearUserPref(CONTROLLER_ID_PREF);
    await controller.unload();
    await target.unload();
  });

  await controller.startup();
  await target.startup();
  const targetAddon = await AddonManager.getAddonByID(target.id);

  const win = await loadInitialView("extension");
  const doc = win.document;
  const toggle = doc.querySelector(".admin-mode-toggle");
  const card = doc.querySelector(`addon-card[addon-id="${target.id}"]`);
  const enableToggle = card.querySelector('[action="toggle-disabled"]');
  const removeAction = card.querySelector('[action="remove"]');
  const installFromFile = doc.querySelector('[action="install-from-file"]');

  ok(toggle.pressed, "Admin Mode starts enabled");
  ok(enableToggle && !enableToggle.disabled, "Add-on enable control is active");

  toggle.buttonEl.click();
  await TestUtils.waitForCondition(
    () => !Services.prefs.getBoolPref(ADMIN_MODE_PREF)
  );
  ok(enableToggle.disabled, "Add-on enable control is disabled while locked");
  ok(removeAction.disabled, "Add-on removal control is disabled while locked");
  ok(installFromFile.disabled, "Add-on installation is disabled while locked");
  enableToggle.click();
  ok(targetAddon.isActive, "A disabled control cannot change add-on state");

  toggle.buttonEl.click();
  await controller.awaitMessage("authorization-requested");
  await TestUtils.waitForCondition(() =>
    Services.prefs.getBoolPref(ADMIN_MODE_PREF)
  );
  ok(toggle.pressed, "A positive controller response enables Admin Mode");
  ok(
    !enableToggle.disabled,
    "Add-on controls are restored after authorization"
  );
});